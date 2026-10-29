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
