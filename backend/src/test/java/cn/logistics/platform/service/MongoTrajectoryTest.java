package cn.logistics.platform.service;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MongoTrajectoryTest {
    private Document point(String id, int sequence, String time) {
        return new Document("eventId", id).append("eventTime", time)
                .append("processedAt", "2026-10-24T06:01:00Z")
                .append("validationStatus", "VALID").append("sourceType", "SIMULATED")
                .append("routeMatchStatus", "NOT_MATCHED").append("anomalies", List.of("OVERSPEED"))
                .append("rawEvent", new Document("sequence", sequence).append("latitude", 23.1291)
                        .append("longitude", 113.2644).append("speedKmh", 90));
    }

    @Test void preservesRawEvidenceAndRepeatedSequenceAcrossRuns() {
        var first = point("a", 1, "2026-10-24T04:00:00Z");
        var second = point("b", 2, "2026-10-24T04:10:00Z").append("processedAt", "2026-10-24T06:01:01Z");
        var nextRun = point("c", 1, "2026-10-24T04:05:00Z").append("processedAt", "2026-10-24T06:01:02Z");
        var result = MongoTelemetryService.mapPoints(List.of(nextRun, second, first, second));
        assertEquals(List.of("a", "b", "c"), result.stream().map(MongoTelemetryService.MapPoint::eventId).toList());
        assertEquals("SIMULATED", result.getFirst().sourceType());
        assertEquals("NOT_MATCHED", result.getFirst().routeMatchStatus());
        assertEquals(List.of("OVERSPEED"), result.getFirst().anomalies());
        assertEquals(result.getFirst().runId(), result.get(1).runId());
        assertNotEquals(result.getFirst().runId(), result.get(2).runId());
    }

    @Test void excludesInvalidOrMissingCoordinatesAndTimestamps() {
        var invalidCoordinate = point("a", 1, "2026-10-24T04:00:00Z");
        invalidCoordinate.get("rawEvent", Document.class).put("latitude", Double.NaN);
        var missingRaw = new Document("validationStatus", "VALID");
        var badTime = point("b", 2, "invalid-time");
        var rejected = point("c", 3, "2026-10-24T05:00:00Z").append("validationStatus", "INVALID");
        assertTrue(MongoTelemetryService.mapPoints(List.of(invalidCoordinate, missingRaw, badTime, rejected)).isEmpty());
    }

    @Test void identifiesMostRecentCompleteRunAndIgnoresPartialRun() {
        var points = new java.util.ArrayList<MongoTelemetryService.MapPoint>();
        for (int sequence = 1; sequence <= 3; sequence++) {
            points.add(mapPoint("complete", sequence, 3, "2026-10-24T06:01:0" + sequence + "Z"));
        }
        points.add(mapPoint("partial", 1, 3, "2026-10-24T06:02:01Z"));
        points.add(mapPoint("partial", 2, 3, "2026-10-24T06:02:02Z"));

        var result = MongoTelemetryService.latestCompleteSimulationRun(points);

        assertEquals("CONNECTED", result.status());
        assertEquals("complete", result.completeRunId());
        assertEquals(3, result.completePointCount());
    }

    private MongoTelemetryService.MapPoint mapPoint(String runId, int sequence, int total, String processedAt) {
        return new MongoTelemetryService.MapPoint(runId + "-" + sequence, sequence, 23.1291, 113.2644, 60,
                "2026-10-24T04:00:00Z", processedAt, "SIMULATED", "NOT_MATCHED", List.of(), runId, total, "WGS84");
    }
}
