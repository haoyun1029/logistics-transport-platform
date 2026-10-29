"""Real five-service integration rehearsal using isolated, explicitly synthetic fixtures.

This does NOT approve any public road or count as verified-truck-route acceptance.
Requires existing VM services, SSH access and a MySQL root password in the process
environment. Retains results, schemas and source offsets; never resets user data.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import socket
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def request(url, body=None, token=None, method=None, raw=False):
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            content = response.read()
            return content if raw else (json.loads(content) if content else None)
    except urllib.error.HTTPError as error:
        # Response errors contain API diagnostics, never request passwords/tokens.
        detail = error.read(4096).decode("utf-8", errors="replace")
        raise RuntimeError("HTTP %s at %s: %s" % (error.code, req.selector, detail)) from None


def until(read, predicate, seconds=60, label="condition"):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = read()
        if predicate(result):
            return result
        time.sleep(1)
    raise RuntimeError("Timed out waiting for " + label)


class Rehearsal:
    def __init__(self, args):
        self.args = args
        self.run = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "_" + secrets.token_hex(2)
        self.database = "logistics_it_" + self.run.lower()
        self.topic = "logistics.gps.it." + self.run.lower()
        self.hdfs_root = "/logistics/transport-statistics/integration/" + self.run
        self.remote = "/tmp/logistics-it-" + self.run
        self.directory = ROOT / "backend" / "target" / "live-chain" / self.run
        self.directory.mkdir(parents=True)
        self.flink = "http://%s:8081" % args.vm_host
        self.api = "http://127.0.0.1:%s" % args.port
        self.original = self.test_job = self.savepoint = self.jar = None
        self.backend = None
        self.logs = []
        self.report = {"runId": self.run, "scope": "ISOLATED_SYNTHETIC_TECHNICAL_REHEARSAL",
                       "publicTruckRoadApproval": False, "database": self.database, "topic": self.topic,
                       "mongoDatabase": self.database, "hdfsRoot": self.hdfs_root, "stages": []}

    def stage(self, name, **details):
        self.report["stages"].append({"name": name, "at": dt.datetime.now(dt.timezone.utc).isoformat(), **details})
        self.save("report.json", self.report)
        print(name, json.dumps(details, ensure_ascii=True), flush=True)

    def save(self, name, value):
        (self.directory / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")

    def ssh(self, command, input_text=None, timeout=180):
        result = subprocess.run(["ssh", "-i", self.args.identity_file, "-o", "BatchMode=yes", "-o",
                                 "ConnectTimeout=8", "root@" + self.args.vm_host, command],
                                input=None if input_text is None else input_text.encode("utf-8"),
                                capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError("VM operation failed: " + result.stderr[-4000:].decode("utf-8", errors="replace"))
        return result.stdout.decode("utf-8", errors="replace")

    def upload(self, source, destination):
        subprocess.run(["scp", "-i", self.args.identity_file, "-o", "BatchMode=yes", str(source),
                        "root@%s:%s" % (self.args.vm_host, destination)], check=True, capture_output=True)

    def api_call(self, path, body=None, token=None, method=None, raw=False):
        return request(self.api + "/api/" + path, body, token, method, raw)

    def prepare(self):
        if not os.environ.get("LOGISTICS_MYSQL_ROOT_PASSWORD"):
            raise RuntimeError("Set LOGISTICS_MYSQL_ROOT_PASSWORD for this process; do not put it in source.")
        with socket.socket() as probe:
            if probe.connect_ex(("127.0.0.1", self.args.port)) == 0:
                raise RuntimeError("Test backend port is already occupied; no process was stopped.")
        jobs = request(self.flink + "/jobs/overview")["jobs"]
        active = [j for j in jobs if j["state"] not in ("FINISHED", "CANCELED", "FAILED")]
        if len(active) != 1 or not active[0]["name"].startswith("Logistics simulated GPS") or active[0]["state"] != "RUNNING":
            raise RuntimeError("Expected exactly one running logistics Flink job; inspect manually.")
        self.original = active[0]["jid"]
        self.save("original-job.json", request(self.flink + "/jobs/" + self.original))
        upload = self.ssh("curl -fsS -F jarfile=@/opt/logistics-artifacts/logistics-gps-deviation-0.1.0.jar http://127.0.0.1:8081/jars/upload")
        self.jar = json.loads(upload)["filename"].split("/")[-1]
        self.ssh("mkdir -p " + shlex.quote(self.remote))
        self.upload(ROOT / "backend/db/schema.sql", self.remote + "/schema.sql")
        # A newly generated name is mandatory. Never import fixtures into logistics_system.
        if not re.fullmatch(r"logistics_it_[a-z0-9_]+", self.database):
            raise RuntimeError("Unsafe test database name")
        sql = ("CREATE DATABASE `%s` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n"
               "GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON `%s`.* "
               "TO 'logistics_app'@'%%';\n" % (self.database, self.database))
        root_password = shlex.quote(os.environ["LOGISTICS_MYSQL_ROOT_PASSWORD"])
        self.ssh("bash -s", "set -euo pipefail\nexport MYSQL_PWD=" + root_password + "\n"
                 "mysql -uroot --protocol=socket <<'SQL'\n" + sql + "SQL\n"
                 "mysql -uroot --protocol=socket " + self.database + " < " + self.remote + "/schema.sql\n")
        self.ssh("KAFKA_HEAP_OPTS='-Xmx128m' /usr/local/kafka/bin/kafka-topics.sh --bootstrap-server "
                 + self.args.vm_host + ":9092 --create --topic " + self.topic + " --partitions 1 --replication-factor 1")
        self.stage("ISOLATED_STORAGE_PREPARED", originalJobId=self.original)
        self.password = secrets.token_urlsafe(20)
        self.bootstrap = secrets.token_urlsafe(24)
        environment = dict(os.environ, SERVER_PORT=str(self.args.port), DB_NAME=self.database,
                           KAFKA_GPS_TOPIC=self.topic, MONGO_DATABASE=self.database,
                           HDFS_LATEST_MANIFEST=self.hdfs_root + "/latest.json", APP_BOOTSTRAP_TOKEN=self.bootstrap)
        environment.pop("LOGISTICS_MYSQL_ROOT_PASSWORD", None)
        self.logs = [open(self.directory / ("backend." + suffix + ".log"), "wb") for suffix in ("stdout", "stderr")]
        self.backend = subprocess.Popen(["cmd.exe", "/c", self.args.maven, "-q", "spring-boot:run"],
                                        cwd=ROOT / "backend", env=environment, stdout=self.logs[0], stderr=self.logs[1],
                                        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        def readiness():
            if self.backend.poll() is not None:
                raise RuntimeError("Isolated backend exited; inspect retained logs")
            try:
                return self.api_call("health")
            except (OSError, RuntimeError):
                return {}
        until(readiness, lambda r: r.get("status") == "UP", seconds=90, label="isolated backend")
        self.api_call("auth/bootstrap", {"setupToken": self.bootstrap, "username": "it_admin",
                                         "password": self.password, "displayName": "ISOLATED TEST ADMIN"})
        self.admin = self.login("it_admin")
        self.api_call("business/users", {"username": "it_dispatch", "password": self.password,
                                         "displayName": "ISOLATED TEST DISPATCHER", "role": "DISPATCHER"}, self.admin)
        self.dispatch = self.login("it_dispatch")
        self.api_call("auth/register", {"username": "it_driver", "password": self.password,
                                        "displayName": "ISOLATED TEST DRIVER"})
        driver = self.api_call("admin/drivers/pending", token=self.admin)[0]
        self.driver_id = driver["id"]
        self.api_call("admin/drivers/%s/review" % self.driver_id, {"approve": True, "note": "ISOLATED TEST ONLY"}, self.admin)
        self.driver = self.login("it_driver")
        self.vehicle_id = self.api_call("business/vehicles", {"plateNumber": "IT-ONLY", "vehicleType": "SYNTHETIC TEST",
                                  "maxLoadTons": 10, "fuelModelLitersPer100km": 30}, self.admin)["id"]
        self.stage("AUTH_AND_REGISTRATION_VERIFIED")

    def login(self, username):
        return self.api_call("auth/login", {"username": username, "password": self.password})["token"]

    def fixtures(self):
        # Direct seeding is confined to a new test schema. No fake evidence is submitted
        # through the real-road review API, and example.invalid is visibly non-evidence.
        seed = """
INSERT INTO routes(id,route_name,origin_city,destination_city,route_status,data_source,created_by)
VALUES(1,'ISOLATED SYNTHETIC PLAN','TEST_A','TEST_B','VERIFIED','ISOLATED_TEST',1),
      (2,'ISOLATED SYNTHETIC ALTERNATIVE','TEST_A','TEST_B','VERIFIED','ISOLATED_TEST',1);
INSERT INTO route_segments(route_id,sequence_number,from_city,to_city,road_name,road_type,distance_km,estimated_minutes,
 geometry_json,map_source,map_request_json,map_response_reference,truck_access_status,truck_evidence_source,truck_evidence_reference,verified_at)
VALUES(1,1,'TEST_A','TEST_B','SYNTHETIC PLAN','TEST',110,120,
 '{"coordinateSystem":"WGS84","coordinates":[[110,30],[110.5,29.9],[111,30]]}',
 'ISOLATED_TEST','{}','NOT_REAL_MAP_DATA','VERIFIED','ISOLATED FIXTURE ONLY','https://example.invalid/fixture',CURRENT_TIMESTAMP(3)),
 (2,1,'TEST_A','TEST_C','SYNTHETIC ALT 1','TEST',45,45,
 '{"coordinateSystem":"WGS84","coordinates":[[110,30],[110.5,30.1]]}',
 'ISOLATED_TEST','{}','NOT_REAL_MAP_DATA','VERIFIED','ISOLATED FIXTURE ONLY','https://example.invalid/fixture',CURRENT_TIMESTAMP(3)),
 (2,2,'TEST_C','TEST_B','SYNTHETIC ALT 2','TEST',45,45,
 '{"coordinateSystem":"WGS84","coordinates":[[110.5,30.1],[111,30]]}',
 'ISOLATED_TEST','{}','NOT_REAL_MAP_DATA','VERIFIED','ISOLATED FIXTURE ONLY','https://example.invalid/fixture',CURRENT_TIMESTAMP(3));
"""
        self.ssh("bash -s", "set -eu\nexport MYSQL_PWD=" + shlex.quote(os.environ["LOGISTICS_MYSQL_ROOT_PASSWORD"])
                 + "\nmysql -uroot --protocol=socket " + self.database + " <<'SQL'\n" + seed + "SQL\n")
        self.task = self.api_call("business/tasks", {"routeId": 1}, self.dispatch)
        self.api_call("business/tasks/%s/assign" % self.task["id"], {"driverId": self.driver_id, "vehicleId": self.vehicle_id}, self.dispatch)
        self.api_call("business/tasks/%s/accept" % self.task["id"], {}, self.driver)
        self.stage("TASK_CREATED_ASSIGNED_ACCEPTED", taskId=self.task["id"], taskCode=self.task["code"])

    def pause_original(self):
        trigger = request(self.flink + "/jobs/" + self.original + "/stop",
                          {"drain": False, "targetDirectory": "file:/var/lib/logistics/flink-savepoints"})["request-id"]
        self.report["stopTriggerId"] = trigger
        self.stage("ORIGINAL_SAVEPOINT_REQUESTED")
        result = until(lambda: request(self.flink + "/jobs/%s/savepoints/%s" % (self.original, trigger)),
                       lambda r: r["status"]["id"] == "COMPLETED", seconds=120, label="original savepoint")
        self.save("original-savepoint.json", result)
        self.savepoint = result.get("operation", {}).get("location")
        if not self.savepoint:
            raise RuntimeError("Savepoint failed: inspect original-savepoint.json; do not submit replacement")
        until(lambda: request(self.flink + "/jobs/" + self.original), lambda r: r["state"] == "FINISHED", label="original stop")
        self.stage("ORIGINAL_SAVED", savepoint=self.savepoint)
        self.test_job = self.submit(self.topic, "logistics-it-" + self.run.lower(), self.database,
                                    "file:/var/lib/logistics/flink-checkpoints/integration/" + self.run)
        self.stage("ISOLATED_FLINK_RUNNING", jobId=self.test_job)

    def submit(self, topic, group, database, checkpoint_dir, savepoint=None):
        body = {"entryClass": "cn.logistics.streaming.GpsStreamingJob", "parallelism": 1,
                "programArgsList": ["--bootstrap-servers", self.args.vm_host + ":9092", "--topic", topic,
                                    "--group-id", group, "--speed-limit-kmh", "80", "--mongo-uri",
                                    "mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000",
                                    "--mongo-database", database, "--checkpoint-dir", checkpoint_dir]}
        if savepoint:
            body.update(savepointPath=savepoint, allowNonRestoredState=False)
            self.save("restore-original-request.json", body)
        job = request(self.flink + "/jars/" + self.jar + "/run", body)["jobid"]
        if savepoint:
            self.report["restoredOriginalJobId"] = job
            self.save("report.json", self.report)
        else:
            # Persist before waiting so cleanup can cancel a failed/deploying test job.
            self.test_job = job
        until(lambda: request(self.flink + "/jobs/" + job), lambda r: r["state"] == "RUNNING", label="Flink RUNNING")
        return job

    def workflow(self):
        path = "business/tasks/%s" % self.task["id"]
        simulation = self.api_call(path + "/simulate", {"actualRouteId": 2}, self.driver)
        self.save("simulation.json", simulation)
        trace = until(lambda: self.api_call(path + "/trajectory", token=self.driver),
                      lambda r: r["status"] == "CONNECTED" and len(r["points"]) == simulation["publishedCount"],
                      seconds=90, label="real Kafka/Flink/Mongo points")
        assert all(p["runId"] == simulation["runId"] and p["sourceType"] == "SIMULATED" for p in trace["points"])
        self.save("trajectory.json", trace)
        self.stage("KAFKA_FLINK_MONGO_VERIFIED", pointCount=len(trace["points"]), runId=simulation["runId"])
        checkpoints = until(lambda: request(self.flink + "/jobs/" + self.test_job + "/checkpoints"),
                            lambda r: r["counts"]["completed"] >= 1, label="completed checkpoint")
        self.save("checkpoints.json", checkpoints)
        # This fixture represents an imported historical test trip. The normal app
        # timestamps are not edited, nor are accelerated event times used as actuals.
        # Keep this explicit so the rehearsal cannot be mistaken for real driving.
        self.ssh("bash -s", "set -eu\nexport MYSQL_PWD=" + shlex.quote(os.environ["LOGISTICS_MYSQL_ROOT_PASSWORD"])
                 + "\nmysql -uroot --protocol=socket " + self.database + " <<'SQL'\n"
                 + "UPDATE transport_tasks SET started_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 90 MINUTE) WHERE id="
                 + str(self.task["id"]) + ";\nSQL\n")
        self.stage("SYNTHETIC_DURATION_FIXTURE", minutes=90, scope="TEST_DATABASE_ONLY_NOT_WALL_CLOCK")
        completed = self.api_call(path + "/complete", {"actualDistanceKm": 100, "actualFuelLiters": 30,
                                                        "comment": "SYNTHETIC FEEDBACK: technical rehearsal, not real transport"}, self.driver)
        self.save("completed-task.json", completed)
        analysis = self.api_call(path + "/match", {}, self.admin)
        self.save("analysis.json", analysis)
        assert analysis["sampleStatus"] == "VALID", analysis.get("reason")
        assert analysis["matchedRoute"]["databaseId"] == 2
        evaluation = self.api_call("business/optimizations", {"planRouteId": 1}, self.admin)
        self.save("optimization.json", evaluation)
        snapshot = evaluation["snapshot"]
        assert snapshot["status"] == "PRELIMINARY", snapshot.get("reason")
        assert snapshot["validSampleCount"] == 1 and snapshot["candidate"]["verificationStatus"] == "CANDIDATE"
        assert snapshot["fuelValueType"] == "ESTIMATED" and len(snapshot["scores"]) == 2
        assert snapshot["scores"][1]["compositeScore"] > snapshot["scores"][0]["compositeScore"]
        assert self.api_call("business/routes/1", token=self.admin)["verificationStatus"] == "VERIFIED"
        self.stage("MYSQL_MATCH_AND_PRELIMINARY_VERIFIED", validSampleCount=1,
                   candidateVersion=snapshot["candidate"]["version"], label=snapshot["label"])
        impossible = self.api_call("business/tasks", {"routeId": 1}, self.dispatch)
        impossible_path = "business/tasks/%s" % impossible["id"]
        self.api_call(impossible_path + "/assign", {"driverId": self.driver_id, "vehicleId": self.vehicle_id}, self.dispatch)
        self.api_call(impossible_path + "/accept", {}, self.driver)
        rejected = self.api_call(impossible_path + "/complete", {"actualDistanceKm": 100, "actualFuelLiters": 25,
                                  "comment": "IMPOSSIBLE DURATION NEGATIVE TEST"}, self.driver)
        assert rejected["sampleStatus"] == "INVALID"
        self.save("impossible-feedback.json", rejected)
        assert self.api_call(impossible_path + "/match", {}, self.admin)["sampleStatus"] == "INVALID"
        online = self.api_call("statistics/od", token=self.admin)
        self.save("online-statistics.json", online)
        assert online["records"][0]["taskCount"] == 2
        assert online["records"][0]["averageActualDistanceKm"] == 100
        assert online["records"][0]["averageActualFuelLiters"] == 30
        self.stage("IMPOSSIBLE_FEEDBACK_RETAINED_AND_EXCLUDED")
        # Raw per-task export, not a SQL aggregate. The test's HDFS root never changes the main pointer.
        (self.directory / "tasks.jsonl").write_bytes(self.api_call("statistics/offline/input", token=self.admin, raw=True))
        for source, target in [(self.directory / "tasks.jsonl", "tasks.jsonl"),
                               (ROOT / "scripts/hadoop/od_mapper.py", "od_mapper.py"),
                               (ROOT / "scripts/hadoop/od_reducer.py", "od_reducer.py"),
                               (ROOT / "scripts/vm/run-od-mapreduce.sh", "run-od-mapreduce.sh")]:
            normalized = self.directory / ("upload-" + target)
            normalized.write_bytes(source.read_bytes().replace(b"\r\n", b"\n"))
            self.upload(normalized, self.remote + "/" + target)
        output = self.ssh("HDFS_STATISTICS_ROOT=" + shlex.quote(self.hdfs_root) + " bash "
                          + self.remote + "/run-od-mapreduce.sh " + self.remote + "/tasks.jsonl", timeout=240)
        (self.directory / "hadoop.stdout.log").write_text(output, encoding="utf-8")
        offline = self.api_call("statistics/offline", token=self.admin)
        self.save("offline-statistics.json", offline)
        assert offline["dataStatus"] == "HADOOP_RESULT_AVAILABLE"
        record = offline["records"][0]
        assert record["taskCount"] == 2 and record["completedFeedbackCount"] == 1
        assert record["excludedSampleCount"] == 1
        assert record["averageActualDistanceKm"] == 100 and record["averageActualFuelLiters"] == 30
        assert record["anomalyCount"] > 0, "Expected real sustained-deviation events from Flink"
        self.stage("HDFS_HADOOP_AND_API_VERIFIED", jobId=offline["jobId"], runId=offline["runId"],
                   anomalyCount=record["anomalyCount"])

    def cleanup(self):
        # Always restore the stopped original job, even after a workflow assertion fails.
        try:
            if not self.savepoint and self.report.get("stopTriggerId"):
                stopped = until(lambda: request(self.flink + "/jobs/%s/savepoints/%s" %
                                                (self.original, self.report["stopTriggerId"])),
                                lambda r: r["status"]["id"] == "COMPLETED", seconds=120, label="stop reconciliation")
                self.savepoint = stopped.get("operation", {}).get("location")
                if not self.savepoint and request(self.flink + "/jobs/" + self.original)["state"] != "RUNNING":
                    raise RuntimeError("Original job stopped without an available savepoint; manual restore required")
            if self.test_job:
                state = request(self.flink + "/jobs/" + self.test_job)["state"]
                if state not in ("FINISHED", "FAILED", "CANCELED"):
                    request(self.flink + "/jobs/" + self.test_job + "?mode=cancel", method="PATCH")
                    until(lambda: request(self.flink + "/jobs/" + self.test_job),
                          lambda r: r["state"] in ("FINISHED", "FAILED", "CANCELED"), label="test job stop")
            if self.savepoint:
                restored = self.submit("logistics.gps.raw", self.args.original_group, "logistics_system",
                                       "file:/var/lib/logistics/flink-checkpoints", self.savepoint)
                checkpoint = request(self.flink + "/jobs/" + restored + "/checkpoints")
                assert checkpoint["counts"]["restored"] == 1
                self.stage("ORIGINAL_FLINK_RESTORED", jobId=restored, restoredCount=1)
        finally:
            if self.backend and self.backend.poll() is None:
                # Stop only the process tree created by this script; never kill a port by name.
                subprocess.run(["taskkill", "/PID", str(self.backend.pid), "/T", "/F"], capture_output=True)
            for log in self.logs:
                log.close()
            self.save("report.json", self.report)

    def execute(self):
        try:
            self.prepare()
            self.fixtures()
            self.pause_original()
            self.workflow()
            self.report["result"] = "WORKFLOW_PASSED"
        except BaseException as error:
            self.report["result"] = "FAILED"
            self.report["failure"] = str(error)
            self.stage("FAILED", errorType=type(error).__name__)
            raise
        finally:
            try:
                self.cleanup()
            except BaseException:
                self.report["result"] = "RESTORE_OR_CLEANUP_FAILED"
                self.stage("RESTORE_OR_CLEANUP_FAILED")
                raise
            else:
                if self.report.get("result") == "WORKFLOW_PASSED":
                    self.report["result"] = "PASSED"
                    self.stage("ISOLATED_REHEARSAL_PASSED")
            finally:
                print("Evidence directory:", self.directory, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--identity-file", required=True)
    parser.add_argument("--maven", required=True)
    parser.add_argument("--vm-host", default="192.168.146.128")
    parser.add_argument("--port", type=int, default=8082)
    parser.add_argument("--original-group", default="logistics-gps-mongo-demo-v1")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9.-]+", args.vm_host) or not 1024 <= args.port <= 65535 or args.port == 8080:
        parser.error("Use a valid VM hostname and an isolated port, never 8080")
    if not Path(args.identity_file).is_file() or not Path(args.maven).is_file():
        parser.error("SSH identity and Maven executable must already exist")
    Rehearsal(args).execute()


if __name__ == "__main__":
    main()
