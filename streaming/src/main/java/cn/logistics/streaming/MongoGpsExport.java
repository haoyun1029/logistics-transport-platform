package cn.logistics.streaming;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import org.bson.Document;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.ascending;

/** Read-only MongoDB export for immutable, task-scoped HDFS archive runs. */
public final class MongoGpsExport {
    private MongoGpsExport() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[1].matches("[A-Za-z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("Usage: MongoGpsExport database taskId existingOutputDirectory");
        }
        String uri = System.getenv("LOGISTICS_MONGODB_URI");
        if (uri == null || uri.trim().isEmpty()) {
            uri = "mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000&socketTimeoutMS=10000";
        }
        Path output = Paths.get(args[2]);
        if (!Files.isDirectory(output)) throw new IllegalArgumentException("Output directory must exist");
        Document counts = new Document();
        try (MongoClient client = MongoClients.create(uri)) {
            client.getDatabase(args[0]).runCommand(new Document("ping", 1));
            for (String name : Arrays.asList("gps_points", "gps_anomalies", "gps_rejected")) {
                MongoCollection<Document> collection = client.getDatabase(args[0]).getCollection(name);
                long count = 0;
                try (BufferedWriter writer = Files.newBufferedWriter(output.resolve(name + ".jsonl"),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                    for (Document event : collection.find(eq("taskId", args[1])).sort(ascending("eventTime", "_id"))) {
                        writer.write(event.toJson());
                        writer.newLine();
                        count++;
                    }
                }
                counts.append(name, count);
            }
        } catch (RuntimeException error) {
            // Never emit connection strings or database exception messages.
            System.err.println("MONGO_EXPORT_FAILED " + error.getClass().getSimpleName());
            System.exit(1);
            return;
        }
        Document manifest = new Document("schemaVersion", 1).append("database", args[0])
                .append("taskId", args[1]).append("exportedAt", Instant.now().toString())
                .append("recordCounts", counts)
                .append("scope", "GPS_AND_POINT_ANOMALIES_ONLY")
                .append("consistency", "PER_COLLECTION_READ_NOT_ATOMIC_SNAPSHOT")
                .append("transportTaskStatisticsIncluded", false);
        Files.write(output.resolve("manifest.json"), manifest.toJson().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW);
        System.out.println("MONGO_EXPORT_OK " + manifest.toJson());
    }
}
