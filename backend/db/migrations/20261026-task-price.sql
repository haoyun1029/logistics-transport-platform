-- Existing tasks keep a NULL rate until a real agreed price is available.
SET @has_price_per_km := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'transport_tasks' AND column_name = 'price_per_km'
);
SET @add_price_per_km := IF(@has_price_per_km = 0,
  'ALTER TABLE transport_tasks ADD COLUMN price_per_km DECIMAL(10,2) NULL AFTER planned_duration_minutes',
  'SELECT 1');
PREPARE add_price_per_km_stmt FROM @add_price_per_km;
EXECUTE add_price_per_km_stmt;
DEALLOCATE PREPARE add_price_per_km_stmt;
