-- Backfill only fixed DEMO_MODE tasks created before task details were added.
-- Values are explicitly marked as demo sample metadata; real historical tasks remain awaiting supplement.
UPDATE transport_tasks t
LEFT JOIN (
    SELECT route_id, SUM(distance_km) AS distance_km, SUM(estimated_minutes) AS duration_minutes
    FROM route_segments GROUP BY route_id
) s ON s.route_id = t.route_id
SET t.cargo_description = COALESCE(t.cargo_description, '演示货物（固定样例）'),
    t.cargo_weight_tons = COALESCE(t.cargo_weight_tons, 8.00),
    t.planned_distance_km = COALESCE(t.planned_distance_km, s.distance_km),
    t.planned_duration_minutes = COALESCE(t.planned_duration_minutes, s.duration_minutes),
    t.delivery_deadline = COALESCE(t.delivery_deadline, DATE_ADD(t.created_at, INTERVAL 1 DAY)),
    t.task_details = COALESCE(t.task_details, '固定演示任务：货物与重量为答辩展示样例，路线指标取自任务计划路线。')
WHERE t.demo_mode = 1;
