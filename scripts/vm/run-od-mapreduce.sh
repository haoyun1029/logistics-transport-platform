#!/usr/bin/env bash
set -euo pipefail
# Input is a raw task JSONL export, NOT pre-aggregated SQL statistics.
# Hadoop local runner saves VM memory; map/shuffle/reduce still run in Hadoop on HDFS.
if [[ $# -ne 1 || ! -f "$1" ]]; then echo 'Usage: bash run-od-mapreduce.sh /tmp/raw-tasks.jsonl' >&2; exit 2; fi
HADOOP_ROOT="${HADOOP_ROOT:-/opt/remodule/hadoop-3.1.3}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAPPER="${MAPPER:-$SCRIPT_DIR/od_mapper.py}"
REDUCER="${REDUCER:-$SCRIPT_DIR/od_reducer.py}"
test -f "$MAPPER" && test -f "$REDUCER"
exec 9>/tmp/logistics-od-mapreduce.lock
flock -n 9 || { echo 'An OD statistics job is already running.' >&2; exit 3; }
export HADOOP_HEAPSIZE_MAX="${HADOOP_HEAPSIZE_MAX:-192}"
export HADOOP_CLIENT_OPTS="${HADOOP_CLIENT_OPTS:-} -Dfile.encoding=UTF-8"
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
base="${HDFS_STATISTICS_ROOT:-/logistics/transport-statistics}/mapreduce/run=$run_id"
input_path="$base/input/tasks.jsonl"
output_path="$base/output"
log_file="/tmp/od-mapreduce-$run_id.log"
"$HADOOP_ROOT/bin/hdfs" dfs -mkdir -p "$base/input"
"$HADOOP_ROOT/bin/hdfs" dfs -put "$1" "$input_path"
"$HADOOP_ROOT/bin/hadoop" jar "$HADOOP_ROOT/share/hadoop/tools/lib/hadoop-streaming-3.1.3.jar" \
  -D mapreduce.framework.name=local -D mapreduce.job.reduces=1 \
  -D mapreduce.task.io.sort.mb=16 -D mapreduce.map.memory.mb=128 -D mapreduce.reduce.memory.mb=128 \
  -D mapreduce.job.name="logistics-od-statistics-$run_id" \
  -files "$MAPPER,$REDUCER" -mapper 'python od_mapper.py' -reducer 'python od_reducer.py' \
  -input "$input_path" -output "$output_path" >"$log_file" 2>&1 || { tail -n 80 "$log_file" >&2; exit 4; }
"$HADOOP_ROOT/bin/hdfs" dfs -test -e "$output_path/_SUCCESS"
job_id="$(sed -n 's/.*Running job: \([^ ]*\).*/\1/p' "$log_file" | tail -n 1)"
input_sha="$(sha256sum "$1" | cut -d ' ' -f 1)"
mapper_sha="$(sha256sum "$MAPPER" | cut -d ' ' -f 1)"
reducer_sha="$(sha256sum "$REDUCER" | cut -d ' ' -f 1)"
manifest="/tmp/od-manifest-$run_id.json"
python - "$run_id" "$base" "$job_id" "$input_sha" "$mapper_sha" "$reducer_sha" >"$manifest" <<'PY'
import datetime,json,sys
run,base,job,sha,mapper_sha,reducer_sha=sys.argv[1:]
print(json.dumps(dict(schemaVersion=1,source='HADOOP_MAPREDUCE',executionMode='LOCAL_ON_HDFS',runId=run,jobId=job,
  inputPath=base+'/input/tasks.jsonl',inputSha256=sha,resultPath=base+'/output/part-00000',
  feedbackValidationVersion='FEEDBACK_PLAUSIBILITY_V1',mapperSha256=mapper_sha,reducerSha256=reducer_sha,
  successPath=base+'/output/_SUCCESS',completedAt=datetime.datetime.utcnow().isoformat()+'Z')))
PY
"$HADOOP_ROOT/bin/hdfs" dfs -put "$manifest" "$base/manifest.json"
# Only expose a completed immutable run through the latest pointer.
"$HADOOP_ROOT/bin/hdfs" dfs -put -f "$manifest" "${HDFS_STATISTICS_ROOT:-/logistics/transport-statistics}/latest.json"
"$HADOOP_ROOT/bin/hdfs" dfs -cat "$output_path/part-00000"
printf '\nHADOOP_OD_STATISTICS_READY run=%s job=%s input_sha256=%s log=%s\n' "$run_id" "$job_id" "$input_sha" "$log_file"
