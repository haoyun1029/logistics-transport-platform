#!/usr/bin/env bash
# Copies one MongoDB export run into HDFS and verifies every file after reading it back.
set -euo pipefail
[[ $# -eq 2 ]] || { echo 'Usage: bash archive-gps-hdfs.sh JOB_JAR TASK_ID' >&2; exit 1; }
job_jar=$1
task_id=$2
[[ -f "$job_jar" && "$task_id" =~ ^[A-Za-z0-9_-]{1,80}$ ]] || { echo 'Invalid JAR or task ID' >&2; exit 1; }
hdfs=/opt/remodule/hadoop-3.1.3/bin/hdfs
export HADOOP_HEAPSIZE_MAX=128
export HADOOP_HEAPSIZE_MIN=32
"$hdfs" dfsadmin -safemode get | grep -F 'Safe mode is OFF' >/dev/null || { echo 'HDFS not ready for writes' >&2; exit 1; }
install -d -m 700 /var/lib/logistics/archive-staging
run_dir=$(mktemp -d /var/lib/logistics/archive-staging/run-$(date +%Y%m%d-%H%M%S)-XXXXXX)
java -Xmx96m -cp "$job_jar" cn.logistics.streaming.MongoGpsExport logistics_system "$task_id" "$run_dir"
archive_dir=/logistics/gps/export_date=$(date +%F)/task_id=$task_id/$(basename "$run_dir")
if "$hdfs" dfs -test -e "$archive_dir"; then echo 'Archive already exists; not overwritten' >&2; exit 1; fi
"$hdfs" dfs -mkdir -p "$archive_dir"
for file in "$run_dir"/*; do
    name=$(basename "$file")
    "$hdfs" dfs -Ddfs.replication=1 -put "$file" "$archive_dir/$name"
    local_hash=$(sha256sum "$file" | awk '{print $1}')
    remote_hash=$("$hdfs" dfs -cat "$archive_dir/$name" | sha256sum | awk '{print $1}')
    [[ "$local_hash" == "$remote_hash" ]] || { echo "Archive verification failed: $name" >&2; exit 1; }
    printf 'HDFS_FILE_VERIFIED %s sha256=%s\n' "$name" "$remote_hash"
done
# A marker is written only after all files have been read back and verified.
"$hdfs" dfs -touchz "$archive_dir/_SUCCESS"
printf 'HDFS_ARCHIVE_OK %s\n' "$archive_dir"
printf 'Staging retained: %s\n' "$run_dir"
printf 'This archive contains GPS records; per-OD transport statistics are not included.\n'
