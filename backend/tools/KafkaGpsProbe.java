import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;

/** Read-only inspection of existing raw events; never commits consumer offsets. */
public class KafkaGpsProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected: bootstrapServers topic taskId");
        Properties properties = new Properties();
        properties.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, args[0]);
        properties.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.setProperty(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        properties.setProperty(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        properties.setProperty(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");
        ObjectMapper json = new ObjectMapper();
        int inspected = 0, matching = 0, invalid = 0, malformed = 0;
        var sequences = new HashSet<Integer>();
        boolean complete;
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            // Manual assignment and disabled auto-commit leave application consumer groups untouched.
            var partitions = consumer.partitionsFor(args[1]).stream()
                    .map(info -> new TopicPartition(args[1], info.partition())).toList();
            if (partitions.isEmpty()) throw new IllegalStateException("Topic has no partitions");
            consumer.assign(partitions);
            Map<TopicPartition, Long> snapshot = consumer.endOffsets(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            complete = false;
            while (System.nanoTime() < deadline && inspected < 5000) {
                complete = partitions.stream().allMatch(p -> consumer.position(p) >= snapshot.get(p));
                if (complete) break;
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.offset() >= snapshot.get(new TopicPartition(record.topic(), record.partition()))) continue;
                    if (++inspected > 5000) break;
                    JsonNode point;
                    try { point = json.readTree(record.value()); }
                    catch (Exception error) { malformed++; continue; }
                    if (point == null || !args[2].equals(point.path("taskId").asText())) continue;
                    matching++;
                    boolean valid = "SIMULATED".equals(point.path("sourceType").asText())
                            && !point.path("vehicleId").asText().isBlank()
                            && !point.path("routeId").asText().isBlank()
                            && !point.path("timestamp").isMissingNode() && !point.path("timestamp").isNull()
                            && point.path("sequence").isIntegralNumber() && point.path("sequence").asInt() > 0
                            && numberInRange(point, "latitude", -90, 90)
                            && numberInRange(point, "longitude", -180, 180)
                            && numberInRange(point, "speedKmh", 0, Double.MAX_VALUE)
                            && !point.has("city") && !point.has("road")
                            && !point.has("matchedCity") && !point.has("matchedRoad");
                    if (valid) sequences.add(point.path("sequence").asInt());
                    else invalid++;
                }
            }
            if (inspected <= 5000) {
                complete = partitions.stream().allMatch(p -> consumer.position(p) >= snapshot.get(p));
            }
        }
        System.out.println(json.writeValueAsString(Map.of(
                "topic", args[1], "taskId", args[2], "recordsInspected", inspected,
                "matchingRecords", matching, "invalidMatchingRecords", invalid,
                "malformedRecords", malformed, "uniqueSequences", sequences.size(),
                "snapshotComplete", complete, "offsetsCommitted", false)));
        if (!complete || matching == 0 || invalid != 0 || malformed != 0) System.exit(2);
    }

    private static boolean numberInRange(JsonNode point, String field, double min, double max) {
        JsonNode value = point.path(field);
        return value.isNumber() && Double.isFinite(value.asDouble())
                && value.asDouble() >= min && value.asDouble() <= max;
    }
}
