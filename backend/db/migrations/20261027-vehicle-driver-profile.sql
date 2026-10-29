-- Add the administrator-owned vehicle profile and its primary driver binding.
-- Existing rows remain readable with nullable/default values; new vehicle records
-- are validated by TransportBusinessService and require a reviewed driver.
ALTER TABLE vehicles
    ADD COLUMN energy_type VARCHAR(24) NOT NULL DEFAULT '柴油' AFTER vehicle_type,
    ADD COLUMN total_weight_tons DECIMAL(10,2) NULL AFTER energy_type,
    ADD COLUMN length_m DECIMAL(8,2) NULL AFTER max_load_tons,
    ADD COLUMN width_m DECIMAL(8,2) NULL AFTER length_m,
    ADD COLUMN height_m DECIMAL(8,2) NULL AFTER width_m,
    ADD COLUMN axle_count INT NULL AFTER height_m,
    ADD COLUMN has_etc BOOLEAN NOT NULL DEFAULT FALSE AFTER axle_count,
    ADD COLUMN driver_id BIGINT NULL AFTER fuel_model_liters_per_100km,
    ADD CONSTRAINT fk_vehicle_driver FOREIGN KEY (driver_id) REFERENCES users(id);
