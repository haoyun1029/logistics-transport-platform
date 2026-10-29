package cn.logistics.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/** Validates raw data only. Route matching requires separately verified road geometry. */
public final class GpsEventProcessor {
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final double speedLimitKmh;

    public GpsEventProcessor(double speedLimitKmh) {
        if (!Double.isFinite(speedLimitKmh) || speedLimitKmh <= 0) {
            throw new IllegalArgumentException("speed-limit-kmh must be positive and finite");
        }
        this.speedLimitKmh = speedLimitKmh;
    }

    public String process(String raw) throws Exception {
        ObjectNode result = json.createObjectNode();
        result.put("eventId", digest(raw));
        result.put("processedAt", Instant.now().toString());
        result.put("routeMatchStatus", "NOT_MATCHED");
        result.put("deviationStatus", "NOT_EVALUATED_NO_VERIFIED_GEOMETRY");
        result.put("eligibleForOptimization", false);
        try {
            JsonNode point = json.readTree(raw);
            if (point == null || !point.isObject()) throw new IllegalArgumentException("JSON_OBJECT_REQUIRED");
            for (String name : new String[]{"taskId", "vehicleId", "routeId"}) {
                if (!point.path(name).isTextual() || point.path(name).asText().trim().isEmpty()) {
                    throw new IllegalArgumentException("MISSING_" + name);
                }
            }
            if (!"SIMULATED".equals(point.path("sourceType").asText())) {
                throw new IllegalArgumentException("SIMULATION_LABEL_REQUIRED");
            }
            for (String name : new String[]{"city", "road", "matchedCity", "matchedRoad", "citySequence", "roadSequence"}) {
                if (point.has(name)) throw new IllegalArgumentException("MATCHED_FIELDS_IN_RAW_EVENT");
            }
            checkNumber(point, "latitude", -90, 90);
            checkNumber(point, "longitude", -180, 180);
            checkNumber(point, "speedKmh", 0, Double.MAX_VALUE);
            if (!point.path("sequence").isIntegralNumber() || !point.path("sequence").canConvertToInt()
                    || point.path("sequence").asInt() < 1) throw new IllegalArgumentException("INVALID_SEQUENCE");
            JsonNode timestamp = point.path("timestamp");
            Instant eventTime;
            if (timestamp.isTextual()) eventTime = Instant.parse(timestamp.asText());
            else if (timestamp.isNumber()) {
                // Kafka JsonSerializer can encode Instant as epoch seconds with fractional nanoseconds.
                BigDecimal seconds = timestamp.decimalValue();
                long whole = seconds.toBigInteger().longValueExact();
                int nanos = seconds.subtract(BigDecimal.valueOf(whole)).movePointRight(9).intValueExact();
                eventTime = Instant.ofEpochSecond(whole, nanos);
            } else throw new IllegalArgumentException("INVALID_TIMESTAMP");
            result.put("taskId", point.path("taskId").asText());
            result.put("vehicleId", point.path("vehicleId").asText());
            result.put("routeId", point.path("routeId").asText());
            result.put("eventTime", eventTime.toString());
            result.put("sourceType", "SIMULATED");
            result.put("validationStatus", "VALID");
            result.set("rawEvent", point);
            result.put("speedLimitKmh", speedLimitKmh);
            if (point.path("speedKmh").asDouble() > speedLimitKmh) {
                result.putArray("anomalies").add("OVERSPEED");
            } else result.putArray("anomalies");
        } catch (IllegalArgumentException | ArithmeticException | java.time.DateTimeException | com.fasterxml.jackson.core.JsonProcessingException error) {
            result.put("validationStatus", "INVALID");
            result.put("exclusionReason", error instanceof IllegalArgumentException
                    && error.getMessage() != null && error.getMessage().matches("[A-Za-z_]+")
                    ? error.getMessage() : "INVALID_JSON_OR_TIMESTAMP");
        }
        return json.writeValueAsString(result);
    }

    private static void checkNumber(JsonNode point, String name, double min, double max) {
        JsonNode value = point.path(name);
        if (!value.isNumber() || !Double.isFinite(value.asDouble())
                || value.asDouble() < min || value.asDouble() > max) {
            throw new IllegalArgumentException("INVALID_" + name);
        }
    }

    private static String digest(String raw) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest((raw == null ? "" : raw).getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
