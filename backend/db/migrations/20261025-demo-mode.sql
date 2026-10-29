-- Add an explicit per-task marker so demo-only samples cannot enter formal optimization.
-- The information_schema guard makes this additive migration safe to re-run.
SET @demo_mode_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'transport_tasks'
      AND column_name = 'demo_mode'
);
SET @demo_mode_sql := IF(
    @demo_mode_column_exists = 0,
    'ALTER TABLE transport_tasks ADD COLUMN demo_mode TINYINT(1) NOT NULL DEFAULT 0 AFTER source_type',
    'SELECT 1'
);
PREPARE demo_mode_statement FROM @demo_mode_sql;
EXECUTE demo_mode_statement;
DEALLOCATE PREPARE demo_mode_statement;
