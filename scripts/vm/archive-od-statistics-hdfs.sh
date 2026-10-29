#!/usr/bin/env bash
set -euo pipefail

# Export the same business statistics shown by /api/statistics/od to HDFS.
# Credentials are supplied by the caller through MYSQL_PWD and never stored here.
MYSQL_USER="${MYSQL_USER:-logistics_app}"
MYSQL_DATABASE="${MYSQL_DATABASE:-logistics_system}"
HDFS_ROOT="${HDFS_ROOT:-/logistics/transport-statistics}"
EXPORT_DATE="${EXPORT_DATE:-$(date -u +%F)}"
RUN_ID="${RUN_ID:-$(date -u +%Y%m%d-%H%M%S)}"
LOCAL_FILE="${LOCAL_FILE:-/tmp/od-statistics-${RUN_ID}.csv}"
HDFS_DIR="${HDFS_ROOT}/export_date=${EXPORT_DATE}/run=${RUN_ID}"

if [[ -z "${MYSQL_PWD:-}" ]]; then
  echo 'MYSQL_PWD must be provided by the caller.' >&2
  exit 2
fi
command -v mysql >/dev/null
command -v hdfs >/dev/null

cat >"${LOCAL_FILE}" <<'CSV'
origin_city,destination_city,task_count,average_actual_elapsed_minutes,average_actual_distance_km,average_actual_fuel_liters,anomaly_count
CSV
MYSQL_PWD="$MYSQL_PWD" mysql --protocol=socket -u"$MYSQL_USER" -N -B "$MYSQL_DATABASE" -e "
SELECT r.origin_city, r.destination_city,
       COUNT(t.id),
       ROUND(AVG(CASE WHEN t.started_at IS NOT NULL AND t.completed_at IS NOT NULL
                      THEN TIMESTAMPDIFF(MINUTE, t.started_at, t.completed_at) END), 2),
       ROUND(AVG(f.actual_distance_km), 2),
       ROUND(AVG(f.actual_fuel_liters), 2),
       COALESCE(SUM(CASE WHEN t.sample_status = 'INVALID' THEN 1 ELSE 0 END), 0)
       + COALESCE(SUM(CASE WHEN t.exclusion_reason IS NOT NULL THEN 1 ELSE 0 END), 0)
       + COALESCE(SUM(CASE WHEN a.action = 'GPS_ANOMALY'
                           THEN COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(a.details_json, '$.count')) AS UNSIGNED), 1)
                           ELSE 0 END), 0)
FROM routes r
LEFT JOIN transport_tasks t ON t.route_id = r.id
LEFT JOIN driver_feedback f ON f.task_id = t.id
LEFT JOIN audit_logs a ON a.entity_id = t.task_code AND a.action = 'GPS_ANOMALY'
GROUP BY r.origin_city, r.destination_city
ORDER BY r.origin_city, r.destination_city;" >>"${LOCAL_FILE}"

HADOOP_HEAPSIZE_MAX="${HADOOP_HEAPSIZE_MAX:-128}" hdfs dfs -mkdir -p "$HDFS_DIR"
HADOOP_HEAPSIZE_MAX="${HADOOP_HEAPSIZE_MAX:-128}" hdfs dfs -put -f "$LOCAL_FILE" "$HDFS_DIR/od-statistics.csv"
HADOOP_HEAPSIZE_MAX="${HADOOP_HEAPSIZE_MAX:-128}" hdfs dfs -cat "$HDFS_DIR/od-statistics.csv" >/tmp/od-statistics-readback-${RUN_ID}.csv
cmp -s "$LOCAL_FILE" "/tmp/od-statistics-readback-${RUN_ID}.csv"
printf 'HDFS_OD_STATISTICS_READY path=%s rows=%s\n' "$HDFS_DIR" "$(($(wc -l <"${LOCAL_FILE}") - 1))"
