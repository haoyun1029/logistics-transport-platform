-- MySQL 5.7 additive migration. Existing tasks and feedback are retained.
-- MySQL 5.7 does not enforce CHECK constraints; Java validates the state machine.
ALTER TABLE transport_tasks MODIFY driver_id BIGINT NULL,
    MODIFY vehicle_id BIGINT NULL,
    MODIFY task_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_ASSIGNMENT';
CREATE TABLE IF NOT EXISTS maintenance_records (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    vehicle_id BIGINT NOT NULL,
    recorded_by BIGINT NOT NULL,
    description VARCHAR(900) NOT NULL,
    serviced_on DATE NOT NULL,
    next_service_on DATE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_maintenance_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    CONSTRAINT fk_maintenance_recorder FOREIGN KEY (recorded_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
