-- Idempotent fixed business sample for the local demonstration.
-- It creates one completed Beijing -> Shanghai transport in MySQL.
-- It does not alter users, passwords, GPS collections or route verification evidence.
USE logistics_system;

SET @admin_id := (SELECT id FROM users WHERE username = 'admin' AND account_status = 'APPROVED' LIMIT 1);
SET @driver_id := (SELECT id FROM users WHERE username = 'demo_driver' AND role = 'DRIVER' AND account_status = 'APPROVED' LIMIT 1);

INSERT INTO vehicles (plate_number, vehicle_type, energy_type, total_weight_tons, max_load_tons,
                      length_m, width_m, height_m, axle_count, has_etc,
                      fuel_model_liters_per_100km, driver_id, vehicle_status)
SELECT '京A·D001', '重型厢式货车', '柴油', 25.00, 18.00,
       12.00, 2.55, 3.90, 6, TRUE, 33.900, @driver_id, 'AVAILABLE'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM vehicles WHERE plate_number = '京A·D001');
SET @vehicle_id := (SELECT id FROM vehicles WHERE plate_number = '京A·D001' LIMIT 1);
UPDATE vehicles SET driver_id=@driver_id, energy_type='柴油', total_weight_tons=25.00,
       length_m=12.00, width_m=2.55, height_m=3.90, axle_count=6, has_etc=TRUE
WHERE id=@vehicle_id;

INSERT INTO routes (route_name, origin_city, destination_city, route_status, data_source,
                    map_request_json, map_response_reference, version_number, created_by)
SELECT '固定演示计划路线：北京—上海', '北京', '上海', 'PENDING_VERIFICATION',
       'DEMO_FIXED_FIXTURE', JSON_OBJECT('source', 'fixed-demo', 'verification', 'pending'),
       'fixture-route-plan-bj-sh', 1, @admin_id
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM routes WHERE route_name = '固定演示计划路线：北京—上海'
);
SET @route_id := (SELECT id FROM routes WHERE route_name = '固定演示计划路线：北京—上海' LIMIT 1);

INSERT INTO route_segments (route_id, sequence_number, from_city, to_city, road_name, road_type,
                            distance_km, estimated_minutes, geometry_json, map_source,
                            map_request_json, map_response_reference, truck_access_status)
SELECT @route_id, 1, '北京', '天津', '京津高速 G2', '高速', 135, 105, NULL, 'DEMO_FIXED_FIXTURE',
       JSON_OBJECT('segment', 'bj-tj'), 'fixture-segment-bj-tj', 'PENDING'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM route_segments WHERE route_id = @route_id AND sequence_number = 1);
INSERT INTO route_segments (route_id, sequence_number, from_city, to_city, road_name, road_type,
                            distance_km, estimated_minutes, geometry_json, map_source,
                            map_request_json, map_response_reference, truck_access_status)
SELECT @route_id, 2, '天津', '济南', '京沪高速 G2', '高速', 320, 220, NULL, 'DEMO_FIXED_FIXTURE',
       JSON_OBJECT('segment', 'tj-jinan'), 'fixture-segment-tj-jinan', 'PENDING'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM route_segments WHERE route_id = @route_id AND sequence_number = 2);
INSERT INTO route_segments (route_id, sequence_number, from_city, to_city, road_name, road_type,
                            distance_km, estimated_minutes, geometry_json, map_source,
                            map_request_json, map_response_reference, truck_access_status)
SELECT @route_id, 3, '济南', '徐州', '京台高速 G3', '高速', 300, 215, NULL, 'DEMO_FIXED_FIXTURE',
       JSON_OBJECT('segment', 'jinan-xuzhou'), 'fixture-segment-jinan-xuzhou', 'PENDING'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM route_segments WHERE route_id = @route_id AND sequence_number = 3);
INSERT INTO route_segments (route_id, sequence_number, from_city, to_city, road_name, road_type,
                            distance_km, estimated_minutes, geometry_json, map_source,
                            map_request_json, map_response_reference, truck_access_status)
SELECT @route_id, 4, '徐州', '南京', '京沪高速 G2', '高速', 280, 195, NULL, 'DEMO_FIXED_FIXTURE',
       JSON_OBJECT('segment', 'xuzhou-nanjing'), 'fixture-segment-xuzhou-nanjing', 'PENDING'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM route_segments WHERE route_id = @route_id AND sequence_number = 4);
INSERT INTO route_segments (route_id, sequence_number, from_city, to_city, road_name, road_type,
                            distance_km, estimated_minutes, geometry_json, map_source,
                            map_request_json, map_response_reference, truck_access_status)
SELECT @route_id, 5, '南京', '上海', '沪蓉高速 G42', '高速', 300, 210, NULL, 'DEMO_FIXED_FIXTURE',
       JSON_OBJECT('segment', 'nanjing-shanghai'), 'fixture-segment-nanjing-shanghai', 'PENDING'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM route_segments WHERE route_id = @route_id AND sequence_number = 5);

INSERT INTO transport_tasks (task_code, route_id, cargo_description, cargo_weight_tons, planned_distance_km,
                             planned_duration_minutes, delivery_deadline, task_details, driver_id, vehicle_id, task_status, started_at,
                             completed_at, source_type, sample_status, matched_route_json,
                             matched_city_sequence, route_difference_recoverable, created_by)
SELECT 'DEMO-2026-001', @route_id, '演示货物（固定样例）', 8.00,
       (SELECT SUM(distance_km) FROM route_segments WHERE route_id=@route_id),
       (SELECT SUM(estimated_minutes) FROM route_segments WHERE route_id=@route_id),
       '2026-10-19 19:10:00.000', '固定演示任务：货物与重量为答辩展示样例，路线指标取自任务计划路线。', @driver_id, @vehicle_id, 'COMPLETED',
       '2026-10-18 01:00:00.000', '2026-10-18 19:10:00.000', 'SIMULATED', 'VALID',
       JSON_OBJECT('citySequence', JSON_ARRAY('北京', '天津', '济南', '南京', '上海'), 'anomalyCount', 12),
       JSON_ARRAY('北京', '天津', '济南', '南京', '上海'), TRUE, @admin_id
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM transport_tasks WHERE task_code = 'DEMO-2026-001');
SET @task_id := (SELECT id FROM transport_tasks WHERE task_code = 'DEMO-2026-001' LIMIT 1);

INSERT INTO driver_feedback (task_id, actual_distance_km, actual_fuel_liters, comment, submitted_at)
SELECT @task_id, 1194.00, 405.00, '固定演示实测反馈；轨迹来源明确标记为 SIMULATED。', '2026-10-18 19:15:00.000'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM driver_feedback WHERE task_id = @task_id);

INSERT INTO audit_logs (actor_user_id, action, entity_type, entity_id, details_json, created_at)
SELECT @admin_id, 'GPS_ANOMALY', 'TRANSPORT_TASK', 'DEMO-2026-001',
       JSON_OBJECT('count', 12, 'sourceType', 'SIMULATED', 'note', 'Flink GPS anomaly summary'),
       '2026-10-18 19:20:00.000'
FROM DUAL WHERE NOT EXISTS (
    SELECT 1 FROM audit_logs WHERE action = 'GPS_ANOMALY' AND entity_id = 'DEMO-2026-001'
);

SELECT 'seeded' AS result, @route_id AS route_id, @task_id AS task_id;
