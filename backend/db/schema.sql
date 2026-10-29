-- Explicit NULL defaults avoid implicit zero timestamps on MySQL 5.7
-- when explicit_defaults_for_timestamp is OFF and strict SQL mode is enabled.
CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    password_change_required BOOLEAN NOT NULL DEFAULT FALSE,
    role VARCHAR(24) NOT NULL,
    account_status VARCHAR(24) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    phone VARCHAR(32),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    reviewed_at TIMESTAMP(3) NULL DEFAULT NULL,
    reviewed_by BIGINT,
    review_note VARCHAR(300),
    super_admin_slot TINYINT GENERATED ALWAYS AS (
        CASE WHEN role = 'SUPER_ADMIN' AND account_status = 'APPROVED' THEN 1 ELSE NULL END
    ) STORED,
    UNIQUE KEY uq_single_approved_super_admin (super_admin_slot),
    CONSTRAINT chk_users_role CHECK (role IN ('SUPER_ADMIN', 'ADMIN', 'DISPATCHER', 'DRIVER')),
    CONSTRAINT chk_users_status CHECK (account_status IN ('PENDING', 'APPROVED', 'REJECTED', 'DISABLED', 'DELETED')),
    CONSTRAINT fk_users_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS api_sessions (
    token_hash CHAR(64) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_api_sessions_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_api_sessions_expiry (expires_at),
    INDEX idx_api_sessions_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS vehicles (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    plate_number VARCHAR(24) NOT NULL UNIQUE,
    vehicle_type VARCHAR(48) NOT NULL,
    energy_type VARCHAR(24) NOT NULL DEFAULT '柴油',
    total_weight_tons DECIMAL(10,2) NULL,
    max_load_tons DECIMAL(10,2) NOT NULL,
    length_m DECIMAL(8,2) NULL,
    width_m DECIMAL(8,2) NULL,
    height_m DECIMAL(8,2) NULL,
    axle_count INT NULL,
    has_etc BOOLEAN NOT NULL DEFAULT FALSE,
    fuel_model_liters_per_100km DECIMAL(8,3) NOT NULL,
    driver_id BIGINT NULL,
    vehicle_status VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE',
    next_service_on DATE NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_vehicle_status CHECK (vehicle_status IN ('AVAILABLE', 'IN_TRANSIT', 'MAINTENANCE')),
    CONSTRAINT fk_vehicle_driver FOREIGN KEY (driver_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS routes (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    route_name VARCHAR(120) NOT NULL,
    origin_city VARCHAR(80) NOT NULL,
    destination_city VARCHAR(80) NOT NULL,
    route_status VARCHAR(24) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    data_source VARCHAR(80),
    map_request_json JSON,
    map_response_reference VARCHAR(512),
    version_number INT NOT NULL DEFAULT 1,
    parent_route_id BIGINT,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    verified_at TIMESTAMP(3) NULL DEFAULT NULL,
    verified_by BIGINT,
    CONSTRAINT chk_route_status CHECK (route_status IN ('DRAFT', 'PENDING_VERIFICATION', 'VERIFIED', 'REJECTED', 'CANDIDATE')),
    CONSTRAINT fk_routes_parent FOREIGN KEY (parent_route_id) REFERENCES routes(id),
    CONSTRAINT fk_routes_creator FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT fk_routes_verifier FOREIGN KEY (verified_by) REFERENCES users(id),
    INDEX idx_routes_od_status (origin_city, destination_city, route_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS route_quotes (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    origin_city VARCHAR(80) NOT NULL,
    destination_city VARCHAR(80) NOT NULL,
    origin_coordinate VARCHAR(96) NOT NULL,
    destination_coordinate VARCHAR(96) NOT NULL,
    request_parameters_json JSON NOT NULL,
    response_json JSON NOT NULL,
    distance_km DECIMAL(10,2) NOT NULL,
    duration_minutes DECIMAL(10,2) NOT NULL,
    data_source VARCHAR(80) NOT NULL,
    truck_access_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    requested_by BIGINT NOT NULL,
    requested_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_route_quote_distance CHECK (distance_km > 0),
    CONSTRAINT chk_route_quote_duration CHECK (duration_minutes > 0),
    CONSTRAINT chk_route_quote_access CHECK (truck_access_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    CONSTRAINT fk_route_quote_user FOREIGN KEY (requested_by) REFERENCES users(id),
    INDEX idx_route_quotes_od_time (origin_city, destination_city, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS route_verification_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    origin_city VARCHAR(80),
    destination_city VARCHAR(80),
    origin_coordinate VARCHAR(96),
    destination_coordinate VARCHAR(96),
    request_parameters_json JSON NOT NULL,
    response_status VARCHAR(48) NOT NULL,
    response_excerpt VARCHAR(1200),
    verification_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    failure_reason VARCHAR(1000) NOT NULL,
    requested_by BIGINT NOT NULL,
    requested_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_route_attempt_status CHECK (verification_status IN ('PENDING', 'FAILED', 'REJECTED')),
    CONSTRAINT fk_route_attempt_user FOREIGN KEY (requested_by) REFERENCES users(id),
    INDEX idx_route_attempts_od_time (origin_city, destination_city, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS route_segments (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    route_id BIGINT NOT NULL,
    sequence_number INT NOT NULL,
    from_city VARCHAR(80) NOT NULL,
    to_city VARCHAR(80) NOT NULL,
    road_name VARCHAR(160) NOT NULL,
    road_type VARCHAR(48) NOT NULL,
    distance_km DECIMAL(10,2) NOT NULL,
    estimated_minutes DECIMAL(10,2) NOT NULL,
    geometry_json JSON,
    map_source VARCHAR(80) NOT NULL,
    map_request_json JSON NOT NULL,
    map_response_reference VARCHAR(512) NOT NULL,
    truck_access_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    truck_evidence_source VARCHAR(160),
    truck_evidence_reference VARCHAR(512),
    verified_at TIMESTAMP(3) NULL DEFAULT NULL,
    verified_by BIGINT,
    CONSTRAINT uq_route_segments_sequence UNIQUE (route_id, sequence_number),
    CONSTRAINT chk_route_segment_distance CHECK (distance_km > 0),
    CONSTRAINT chk_route_segment_time CHECK (estimated_minutes > 0),
    CONSTRAINT chk_route_segment_truck_status CHECK (truck_access_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    CONSTRAINT fk_route_segments_route FOREIGN KEY (route_id) REFERENCES routes(id),
    CONSTRAINT fk_route_segments_verifier FOREIGN KEY (verified_by) REFERENCES users(id),
    INDEX idx_route_segments_nodes (from_city, to_city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS transport_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_code VARCHAR(40) NOT NULL UNIQUE,
    route_id BIGINT NOT NULL,
    cargo_description VARCHAR(120),
    cargo_weight_tons DECIMAL(10,3),
    planned_distance_km DECIMAL(12,2),
    planned_duration_minutes DECIMAL(12,2),
    price_per_km DECIMAL(10,2) NULL,
    delivery_deadline DATETIME(3),
    task_details VARCHAR(1000),
    driver_id BIGINT,
    vehicle_id BIGINT,
    task_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_ASSIGNMENT',
    started_at TIMESTAMP(3) NULL DEFAULT NULL,
    completed_at TIMESTAMP(3) NULL DEFAULT NULL,
    source_type VARCHAR(24) NOT NULL DEFAULT 'SIMULATED',
    demo_mode TINYINT(1) NOT NULL DEFAULT 0,
    sample_status VARCHAR(24) NOT NULL DEFAULT 'PENDING_MATCH',
    exclusion_reason VARCHAR(300),
    matched_route_json JSON,
    matched_city_sequence JSON,
    route_difference_recoverable BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_task_status CHECK (task_status IN ('PENDING_ASSIGNMENT', 'PENDING_ACCEPTANCE', 'IN_TRANSIT', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT chk_task_source CHECK (source_type IN ('SIMULATED', 'DEVICE')),
    CONSTRAINT chk_task_sample_status CHECK (sample_status IN ('PENDING_MATCH', 'VALID', 'INVALID')),
    CONSTRAINT fk_tasks_route FOREIGN KEY (route_id) REFERENCES routes(id),
    CONSTRAINT fk_tasks_driver FOREIGN KEY (driver_id) REFERENCES users(id),
    CONSTRAINT fk_tasks_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    CONSTRAINT fk_tasks_creator FOREIGN KEY (created_by) REFERENCES users(id),
    INDEX idx_tasks_driver_status (driver_id, task_status),
    INDEX idx_tasks_route_status (route_id, task_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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

CREATE TABLE IF NOT EXISTS driver_feedback (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL UNIQUE,
    actual_distance_km DECIMAL(10,2) NOT NULL,
    actual_fuel_liters DECIMAL(10,2) NOT NULL,
    comment VARCHAR(900),
    submitted_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_feedback_distance CHECK (actual_distance_km > 0),
    CONSTRAINT chk_feedback_fuel CHECK (actual_fuel_liters > 0),
    CONSTRAINT fk_feedback_task FOREIGN KEY (task_id) REFERENCES transport_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_user_id BIGINT NOT NULL,
    action VARCHAR(80) NOT NULL,
    entity_type VARCHAR(48) NOT NULL,
    entity_id VARCHAR(80),
    details_json JSON,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES users(id),
    INDEX idx_audit_entity (entity_type, entity_id),
    INDEX idx_audit_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS route_optimization_runs (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    origin_city VARCHAR(80) NOT NULL,
    destination_city VARCHAR(80) NOT NULL,
    plan_route_id BIGINT NOT NULL,
    candidate_route_id BIGINT NOT NULL,
    sample_count INT NOT NULL,
    recommendation_status VARCHAR(48) NOT NULL,
    estimate_source VARCHAR(160) NOT NULL,
    time_min DECIMAL(12,3) NOT NULL,
    time_max DECIMAL(12,3) NOT NULL,
    distance_min DECIMAL(12,3) NOT NULL,
    distance_max DECIMAL(12,3) NOT NULL,
    fuel_min DECIMAL(12,3) NOT NULL,
    fuel_max DECIMAL(12,3) NOT NULL,
    time_weight DECIMAL(8,6) NOT NULL,
    distance_weight DECIMAL(8,6) NOT NULL,
    fuel_weight DECIMAL(8,6) NOT NULL,
    time_score DECIMAL(8,3) NOT NULL,
    distance_score DECIMAL(8,3) NOT NULL,
    fuel_score DECIMAL(8,3) NOT NULL,
    composite_score DECIMAL(8,3) NOT NULL,
    selection_reason TEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_optimization_plan FOREIGN KEY (plan_route_id) REFERENCES routes(id),
    CONSTRAINT fk_optimization_candidate FOREIGN KEY (candidate_route_id) REFERENCES routes(id),
    INDEX idx_optimization_od (origin_city, destination_city, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS system_config (
    id INT NOT NULL PRIMARY KEY,
    version_number BIGINT NOT NULL,
    config_json JSON NOT NULL,
    updated_by BIGINT,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS actual_route_analyses (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    sample_status VARCHAR(24) NOT NULL,
    snapshot_json JSON NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_analysis_task FOREIGN KEY(task_id) REFERENCES transport_tasks(id),
    INDEX idx_analysis_task (task_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS optimization_evaluations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    plan_route_id BIGINT NOT NULL,
    candidate_route_id BIGINT,
    recommendation_status VARCHAR(48) NOT NULL,
    snapshot_json JSON NOT NULL,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_evaluation_plan FOREIGN KEY(plan_route_id) REFERENCES routes(id),
    CONSTRAINT fk_evaluation_candidate FOREIGN KEY(candidate_route_id) REFERENCES routes(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

