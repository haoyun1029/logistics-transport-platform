-- Store the next scheduled service date on the vehicle so drivers can see a read-only reminder.
-- Existing dates are copied from maintenance records; no history is removed.
SET @next_service_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'vehicles'
      AND column_name = 'next_service_on'
);
SET @backfill_legacy_service_dates := IF(@next_service_column_exists = 0, 1, 0);
SET @next_service_sql := IF(
    @next_service_column_exists = 0,
    'ALTER TABLE vehicles ADD COLUMN next_service_on DATE NULL AFTER vehicle_status',
    'SELECT 1'
);
PREPARE next_service_statement FROM @next_service_sql;
EXECUTE next_service_statement;
DEALLOCATE PREPARE next_service_statement;

UPDATE vehicles v
SET next_service_on = (
    SELECT MAX(m.next_service_on)
    FROM maintenance_records m
    WHERE m.vehicle_id = v.id
)
WHERE v.next_service_on IS NULL
  AND @backfill_legacy_service_dates = 1
  AND EXISTS (SELECT 1 FROM maintenance_records m WHERE m.vehicle_id = v.id AND m.next_service_on IS NOT NULL);
