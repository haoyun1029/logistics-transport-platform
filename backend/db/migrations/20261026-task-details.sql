-- Task dispatch details: cargo, weight, planned metrics, deadline and instructions.
-- Existing tasks remain readable; their new fields are nullable for backwards compatibility.
ALTER TABLE transport_tasks
    ADD COLUMN cargo_description VARCHAR(120) NULL AFTER route_id,
    ADD COLUMN cargo_weight_tons DECIMAL(10,3) NULL AFTER cargo_description,
    ADD COLUMN planned_distance_km DECIMAL(12,2) NULL AFTER cargo_weight_tons,
    ADD COLUMN planned_duration_minutes DECIMAL(12,2) NULL AFTER planned_distance_km,
    ADD COLUMN delivery_deadline DATETIME(3) NULL AFTER planned_duration_minutes,
    ADD COLUMN task_details VARCHAR(1000) NULL AFTER delivery_deadline;
