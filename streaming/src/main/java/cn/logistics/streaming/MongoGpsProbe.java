package cn.logistics.streaming;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.HashSet;
import java.util.Set;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.descending;

/** Read-only VM-side evidence tool. Uses the bundled driver, no mongosh needed. */
public final class MongoGpsProbe {
    private MongoGpsProbe() { }

    public static void main(String[] args) {
        if (args.length > 3) {
            System.err.println("Usage: MongoGpsProbe [database] [taskId] [minimumPoints]");
            System.exit(1);
        }
        String database = args.length > 0 ? args[0] : "logistics_system";
        String taskId = args.length > 1 ? args[1] : "DEMO-2026-002";
        long minimum = args.length > 2 ? Long.parseLong(args[2]) : 0;
        if (minimum < 0) throw new IllegalArgumentException("minimumPoints must be nonnegative");
        String uri = System.getenv("LOGISTICS_MONGODB_URI");
        if (uri == null || uri.trim().isEmpty()) {
            uri = "mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000&connectTimeoutMS=5000&socketTimeoutMS=10000";
        }
        try (MongoClient client = MongoClients.create(uri)) {
            MongoDatabase db = client.getDatabase(database);
            db.runCommand(new Document("ping", 1));
            Document build = db.runCommand(new Document("buildInfo", 1));
            Bson task = eq("taskId", taskId);
            MongoCollection<Document> points = db.getCollection("gps_points");
            long count = points.countDocuments(task);
            long anomalies = db.getCollection("gps_anomalies").countDocuments(task);
            long rejected = db.getCollection("gps_rejected").countDocuments(task);
            long simulated = points.countDocuments(and(task, eq("sourceType", "SIMULATED")));
            long unmatched = points.countDocuments(and(task, eq("routeMatchStatus", "NOT_MATCHED"),
                    eq("eligibleForOptimization", false)));
            Set<Integer> sequences = new HashSet<>();
            // Bounded to keep this demonstration probe cheap on the small VM.
            for (Document point : points.find(task).projection(new Document("rawEvent.sequence", 1)).limit(5000)) {
                Document raw = point.get("rawEvent", Document.class);
                if (raw != null && raw.get("sequence") instanceof Number) {
                    sequences.add(((Number) raw.get("sequence")).intValue());
                }
            }
            Document result = new Document("status", "MONGO_READ_OK")
                    .append("version", build.getString("version"))
                    .append("database", database).append("taskId", taskId)
                    .append("points", count).append("anomalyPoints", anomalies).append("rejected", rejected)
                    .append("simulatedPoints", simulated).append("notMatchedAndIneligible", unmatched)
                    .append("uniqueSequencesInFirst5000", sequences.size());
            System.out.println(result.toJson());
            for (Document point : points.find(task).sort(descending("eventTime")).limit(3)
                    .projection(new Document("taskId", 1).append("eventTime", 1).append("sourceType", 1)
                            .append("validationStatus", 1).append("routeMatchStatus", 1)
                            .append("eligibleForOptimization", 1).append("anomalies", 1))) {
                System.out.println("MONGO_GPS_SAMPLE " + point.toJson());
            }
            if (count < minimum || simulated != count || unmatched != count) {
                System.err.println("MONGO_EVIDENCE_INCOMPLETE: insufficient points or unexpected processing flags");
                System.exit(2);
            }
        } catch (RuntimeException error) {
            // Connection strings may contain credentials; do not print exception messages or URI.
            System.err.println("MONGO_CHECK_FAILED " + error.getClass().getSimpleName()
                    + ": inspect the VM MongoDB service and configured URI locally.");
            System.exit(1);
        }
    }
}
