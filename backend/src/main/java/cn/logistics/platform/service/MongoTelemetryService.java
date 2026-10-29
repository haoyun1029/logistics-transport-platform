package cn.logistics.platform.service;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.descending;

/** Read-only view of the Flink evidence collections. */
@Service
public class MongoTelemetryService {

    private final String uri;
    private final String database;
    private final String defaultTaskId;

    public MongoTelemetryService(@Value("${app.mongo.uri}") String uri,
                                 @Value("${app.mongo.database}") String database,
                                 @Value("${app.mongo.task-id:DEMO-2026-002}") String defaultTaskId) {
        this.uri = uri;
        this.database = database;
        this.defaultTaskId = defaultTaskId;
    }

    /** Probe the same configured connection as telemetry, including any SSH tunnel. */
    public ConnectionHealth connectionHealth() {
        return connectionHealth(() -> MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .applyToClusterSettings(settings -> settings.serverSelectionTimeout(1200, TimeUnit.MILLISECONDS))
                .applyToSocketSettings(settings -> settings.connectTimeout(1200, TimeUnit.MILLISECONDS)
                        .readTimeout(1200, TimeUnit.MILLISECONDS))
                .build()));
    }

    ConnectionHealth connectionHealth(Supplier<MongoClient> clients) {
        String endpoint = "后端配置的 MongoDB";
        try {
            // Hosts omit username, password, database and URI query options.
            endpoint = String.join(", ", new ConnectionString(uri).getHosts());
            try (MongoClient client = clients.get()) {
                Document result = client.getDatabase(database).runCommand(new Document("ping", 1));
                if (!(result.get("ok") instanceof Number ok) || ok.doubleValue() != 1.0) {
                    throw new IllegalStateException("MongoDB ping did not succeed");
                }
            }
            return new ConnectionHealth(endpoint, "CONNECTED", "后端 MongoDB ping 成功（使用轨迹读取连接）");
        } catch (RuntimeException exception) {
            // Driver errors may include credentials or topology details; do not return them.
            return new ConnectionHealth(endpoint, "UNAVAILABLE", "后端 MongoDB ping 失败，请检查服务及 SSH 隧道");
        }
    }

    public TelemetrySummary summary(String requestedTaskId) {
        String taskId = requestedTaskId == null || requestedTaskId.isBlank() ? defaultTaskId : requestedTaskId;
        try (MongoClient client = MongoClients.create(uri)) {
            MongoDatabase db = client.getDatabase(database);
            MongoCollection<Document> points = db.getCollection("gps_points");
            MongoCollection<Document> anomalies = db.getCollection("gps_anomalies");
            MongoCollection<Document> rejected = db.getCollection("gps_rejected");
            long pointCount = points.countDocuments(eq("taskId", taskId));
            long anomalyCount = anomalies.countDocuments(eq("taskId", taskId));
            long rejectedCount = rejected.countDocuments(eq("taskId", taskId));
            List<Evidence> recent = points.find(eq("taskId", taskId))
                    .sort(descending("rawEvent.sequence"))
                    .limit(3)
                    .map(MongoTelemetryService::evidence)
                    .into(new java.util.ArrayList<>());
            return new TelemetrySummary(taskId, "CONNECTED", database, pointCount, anomalyCount,
                    rejectedCount, pointCount > 0, recent, "Flink MongoDB sink read succeeded");
        } catch (RuntimeException exception) {
            return new TelemetrySummary(taskId, "UNAVAILABLE", database, 0, 0, 0,
                    false, List.of(), "MongoDB read unavailable; check the VM-local service or SSH tunnel");
        }
    }

    public Trajectory trajectory(String taskId) {
        try (MongoClient client = MongoClients.create(uri)) {
            List<Document> documents = client.getDatabase(database).getCollection("gps_points")
                    .find(eq("taskId", taskId)).sort(descending("processedAt")).limit(2001)
                    .into(new ArrayList<>());
            boolean truncated = documents.size() > 2000;
            if (truncated) documents = documents.subList(0, 2000);
            List<MapPoint> points = mapPoints(documents);
            return new Trajectory(taskId, "CONNECTED", "FLINK_MONGODB", Instant.now(),
                    truncated, points, "原始模拟 GPS，未道路匹配；历史回放不代表车辆实时位置。");
        } catch (RuntimeException exception) {
            return new Trajectory(taskId, "UNAVAILABLE", "FLINK_MONGODB", Instant.now(), false,
                    List.of(), "轨迹读取失败，请检查 MongoDB 服务或 SSH 隧道；不使用虚构 GPS 替代。");
        }
    }

    public SimulationCheck simulationCheck(String taskId) {
        Trajectory trajectory = trajectory(taskId);
        if (!"CONNECTED".equals(trajectory.status()))
            return new SimulationCheck(trajectory.status(), null, 0);
        return latestCompleteSimulationRun(trajectory.points());
    }

    static SimulationCheck latestCompleteSimulationRun(List<MapPoint> points) {
        var runs = new LinkedHashMap<String, SimulationRunAccumulator>();
        for (MapPoint point : points) {
            if (point.runId() == null || point.runId().isBlank()
                    || point.totalPointCount() == null || point.totalPointCount() < 1) continue;
            var run = runs.computeIfAbsent(point.runId(), ignored -> new SimulationRunAccumulator());
            if (run.expectedCount != null && !run.expectedCount.equals(point.totalPointCount())) run.consistent = false;
            else run.expectedCount = point.totalPointCount();
            run.sequences.add(point.sequence());
            if (run.latestProcessedAt == null || point.processedAt().compareTo(run.latestProcessedAt) > 0)
                run.latestProcessedAt = point.processedAt();
        }
        String latestRunId = null;
        SimulationRunAccumulator latestRun = null;
        for (var entry : runs.entrySet()) {
            var run = entry.getValue();
            if (!run.isComplete()) continue;
            if (latestRun == null || run.latestProcessedAt.compareTo(latestRun.latestProcessedAt) > 0) {
                latestRunId = entry.getKey();
                latestRun = run;
            }
        }
        return new SimulationCheck("CONNECTED", latestRunId, latestRun == null ? 0 : latestRun.expectedCount);
    }

    private static final class SimulationRunAccumulator {
        private final java.util.Set<Integer> sequences = new java.util.HashSet<>();
        private Integer expectedCount;
        private String latestProcessedAt;
        private boolean consistent = true;

        private boolean isComplete() {
            return consistent && expectedCount != null && sequences.size() == expectedCount
                    && sequences.contains(1) && sequences.contains(expectedCount);
        }
    }

    // Keep raw observations separate from city/road matching, and never invent missing coordinates.
    static List<MapPoint> mapPoints(List<Document> documents) {
        var unique = new LinkedHashMap<String, MapPoint>();
        for (Document doc : documents) {
            Document raw = doc.get("rawEvent", Document.class);
            if (raw == null || !"VALID".equals(doc.getString("validationStatus"))
                    || !"SIMULATED".equals(doc.getString("sourceType"))) continue;
            Number lat = raw.get("latitude", Number.class);
            Number lng = raw.get("longitude", Number.class);
            Number speed = raw.get("speedKmh", Number.class);
            Number sequence = raw.get("sequence", Number.class);
            if (lat == null || lng == null || speed == null || sequence == null
                    || !Double.isFinite(lat.doubleValue()) || Math.abs(lat.doubleValue()) > 90
                    || !Double.isFinite(lng.doubleValue()) || Math.abs(lng.doubleValue()) > 180
                    || !Double.isFinite(speed.doubleValue()) || speed.doubleValue() < 0
                    || sequence.intValue() < 1) continue;
            String eventTime = doc.getString("eventTime");
            String processedAt = doc.getString("processedAt");
            try {
                Instant.parse(eventTime);
                Instant.parse(processedAt);
            } catch (RuntimeException invalidTime) { continue; }
            String id = doc.getString("eventId");
            if (id == null || id.isBlank()) continue;
            List<String> anomalies = doc.getList("anomalies", String.class, List.of());
            unique.putIfAbsent(id, new MapPoint(id, sequence.intValue(), lat.doubleValue(), lng.doubleValue(),
                    speed.doubleValue(), eventTime, processedAt, doc.getString("sourceType"),
                    doc.getString("routeMatchStatus"), anomalies, raw.getString("simulationRunId"),raw.getInteger("totalPointCount"),raw.getString("coordinateSystem")));
        }
        // Event times can extend into the future in accelerated simulations. Keep separate
        // publication runs in processing order instead of interleaving their future event times.
        List<MapPoint> ordered = unique.values().stream().sorted(Comparator.comparing((MapPoint point) -> Instant.parse(point.processedAt()))
                .thenComparingInt(MapPoint::sequence)).toList();
        var result = new ArrayList<MapPoint>();
        String runId = null;
        int previousSequence = 0;
        for (MapPoint point : ordered) {
            if (point.totalPointCount()!=null && point.runId()!=null)runId=point.runId();
            else if (runId == null || point.sequence() <= previousSequence) runId = point.eventId();
            result.add(new MapPoint(point.eventId(), point.sequence(), point.latitude(), point.longitude(),
                    point.speedKmh(), point.eventTime(), point.processedAt(), point.sourceType(),
                    point.routeMatchStatus(), point.anomalies(), runId,point.totalPointCount(),point.coordinateSystem()));
            previousSequence = point.sequence();
        }
        return List.copyOf(result);
    }

    private static Evidence evidence(Document doc) {
        Document raw = doc.get("rawEvent", Document.class);
        Integer sequence = raw == null ? null : raw.getInteger("sequence");
        String validation = doc.getString("validationStatus");
        String match = doc.getString("routeMatchStatus");
        String source = doc.getString("sourceType");
        return new Evidence(sequence, validation, match, source, doc.getString("deviationStatus"));
    }

    public record TelemetrySummary(String taskId, String status, String database, long pointCount,
                                   long anomalyCount, long rejectedCount, boolean evidenceAvailable,
                                   List<Evidence> recentEvidence, String detail) {}

    public record ConnectionHealth(String endpoint, String status, String detail) {}

    public record SimulationCheck(String status, String completeRunId, int completePointCount) {
        public boolean hasCompleteRun() { return completeRunId != null; }
    }

    public record Evidence(Integer sequence, String validationStatus, String routeMatchStatus,
                           String sourceType, String deviationStatus) {}

    public record MapPoint(String eventId, int sequence, double latitude, double longitude, double speedKmh,
                           String eventTime, String processedAt, String sourceType, String routeMatchStatus,
                           List<String> anomalies, String runId,Integer totalPointCount,String coordinateSystem) {}

    public record Trajectory(String taskId, String status, String source, Instant fetchedAt,
                             boolean truncated, List<MapPoint> points, String detail) {}
}
