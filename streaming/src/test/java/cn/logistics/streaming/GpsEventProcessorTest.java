package cn.logistics.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GpsEventProcessorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final GpsEventProcessor processor = new GpsEventProcessor(80);

    private String point(String timestamp, double speed) {
        return "{\"taskId\":\"DEMO-2026-002\",\"vehicleId\":\"v1\",\"routeId\":\"r1\","
                + "\"timestamp\":" + timestamp + ",\"latitude\":23.1,\"longitude\":113.2,\"sequence\":1,"
                + "\"sourceType\":\"SIMULATED\",\"speedKmh\":" + speed + "}";
    }

    @Test
    void detectsOverspeedWithoutInventingRouteMatching() throws Exception {
        JsonNode result = json.readTree(processor.process(point("\"2026-10-23T07:00:00Z\"", 81)));
        assertEquals("VALID", result.path("validationStatus").asText());
        assertEquals("OVERSPEED", result.path("anomalies").get(0).asText());
        assertEquals("NOT_MATCHED", result.path("routeMatchStatus").asText());
        assertFalse(result.path("eligibleForOptimization").asBoolean());
        assertFalse(result.path("rawEvent").has("citySequence"));
    }

    @Test
    void acceptsEpochSecondsAndDoesNotFlagTheThresholdItself() throws Exception {
        JsonNode result = json.readTree(processor.process(point("1792738800", 80)));
        assertEquals("VALID", result.path("validationStatus").asText());
        assertEquals("2026-10-23T07:00:00Z", result.path("eventTime").asText());
        assertTrue(result.path("anomalies").isEmpty());
    }

    @Test
    void preservesFractionalTimestampAndRejectsImpossibleTimestamp() throws Exception {
        JsonNode result = json.readTree(processor.process(point("1792738800.123456789", 60)));
        assertEquals("2026-10-23T07:00:00.123456789Z", result.path("eventTime").asText());
        assertEquals("INVALID", json.readTree(processor.process(point("1e100", 60)))
                .path("validationStatus").asText());
    }

    @Test
    void rejectsBadCoordinatesAndMatchedFieldsInRawData() throws Exception {
        String input = point("\"2026-10-23T07:00:00Z\"", 60);
        assertEquals("INVALID", json.readTree(processor.process(input.replace("23.1", "123.1")))
                .path("validationStatus").asText());
        assertEquals("MATCHED_FIELDS_IN_RAW_EVENT", json.readTree(processor.process(input.replace("\"sequence\":1", "\"matchedCity\":\"X\",\"sequence\":1")))
                .path("exclusionReason").asText());
    }

    @Test
    void replayHasStableIdAndMalformedInputIsRetainedAsRejectedEvidence() throws Exception {
        String input = point("\"2026-10-23T07:00:00Z\"", 60);
        assertEquals(json.readTree(processor.process(input)).path("eventId"), json.readTree(processor.process(input)).path("eventId"));
        JsonNode rejected = json.readTree(processor.process("not-json-secret-content"));
        assertEquals("INVALID", rejected.path("validationStatus").asText());
        assertFalse(rejected.toString().contains("secret-content"));
    }
}
