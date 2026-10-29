#!/usr/bin/env bash
set -euo pipefail

# Run inside the VM. Uses the running REST service; no Flink installation path is required.
if [ "$#" -lt 1 ]; then
    echo 'Usage: bash submit-gps-job.sh /tmp/logistics-gps-streaming-0.1.0.jar [job arguments...]' >&2
    exit 1
fi
job_jar="$1"
shift
if [ ! -f "$job_jar" ]; then
    echo 'Job JAR does not exist. Upload it with FinalShell first.' >&2
    exit 1
fi
flink_rest="${FLINK_REST_URL:-http://127.0.0.1:8081}"
if command -v python3 >/dev/null 2>&1; then
    json_python=python3
elif command -v python >/dev/null 2>&1; then
    json_python=python
else
    echo 'Python is needed to parse REST responses.' >&2
    exit 1
fi
# CentOS 7 curl lacks --fail-with-body. Capture status and body separately.
rest_request() (
    stage="$1"
    endpoint="$2"
    shift 2
    response_file="$(mktemp "${TMPDIR:-/tmp}/logistics-flink-response.XXXXXX")"
    trap 'rm -f "$response_file"' EXIT
    printf '[%s] Requesting Flink...\n' "$stage" >&2
    if http_status="$(curl --silent --show-error --connect-timeout 5 --max-time 120 \
        --output "$response_file" --write-out '%{http_code}' "$@" "$flink_rest$endpoint")"; then
        case "$http_status" in
            2??) cat "$response_file" ;;
            *)
                printf '[%s] HTTP %s. Flink response follows:\n' "$stage" "$http_status" >&2
                head -c 65536 "$response_file" >&2
                printf '\nSTOPPED: no automatic retry. Share this stage and response for diagnosis.\n' >&2
                exit 1
                ;;
        esac
    else
        curl_status="$?"
        printf '[%s] Network request failed (curl exit %s).\n' "$stage" "$curl_status" >&2
        exit "$curl_status"
    fi
)
overview="$(rest_request CHECK_VERSION /overview)"
flink_version="$(printf '%s' "$overview" | "$json_python" -c 'import json,sys; print(json.load(sys.stdin)["flink-version"])')"
if [ "$flink_version" != '1.17.2' ]; then
    echo "Expected Flink 1.17.2; found $flink_version. Stop and verify compatibility." >&2
    exit 1
fi
jobs="$(rest_request CHECK_ACTIVE_JOBS /jobs/overview)"
active_jobs="$(printf '%s' "$jobs" | "$json_python" -c 'import json,sys; print(sum(1 for j in json.load(sys.stdin).get("jobs",[]) if j.get("name", "").startswith("Logistics simulated GPS") and j.get("state") not in ("FINISHED","CANCELED","FAILED")))')"
if [ "$active_jobs" != '0' ]; then
    echo 'A logistics GPS job is already active. Inspect it in Flink Web UI before submitting another.' >&2
    exit 1
fi
upload="$(rest_request UPLOAD_JAR /jars/upload -F "jarfile=@$job_jar")"
jar_id="$(printf '%s' "$upload" | "$json_python" -c 'import json,os,sys; print(os.path.basename(json.load(sys.stdin)["filename"]))')"
run_body="$("$json_python" -c 'import json,sys; print(json.dumps({"entryClass":"cn.logistics.streaming.GpsStreamingJob","parallelism":1,"programArgsList":sys.argv[1:]}))' "$@")"
result="$(rest_request RUN_JOB "/jars/$jar_id/run" -H 'Content-Type: application/json' -d "$run_body")"
job_id="$(printf '%s' "$result" | "$json_python" -c 'import json,sys; print(json.load(sys.stdin)["jobid"])')"
printf 'SUBMITTED jobId=%s\n' "$job_id"
printf 'Inspect status: curl -s %s/jobs/%s\n' "$flink_rest" "$job_id"
echo 'Submission is not a success check. Wait for RUNNING and inspect TaskManager stdout for GPS_PROCESSED.'
