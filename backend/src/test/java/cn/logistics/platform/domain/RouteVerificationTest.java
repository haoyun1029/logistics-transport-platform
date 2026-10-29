package cn.logistics.platform.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteVerificationTest {

    @Test
    void aRouteIsNotVerifiedUntilEveryAdjacentSegmentHasEvidence() {
        RouteSegment missingEvidence = segment("A", "B", null);
        RouteSegment verified = segment("B", "C", "https://traffic.example/permit").withSequence(2);

        RouteDefinition route = new RouteDefinition("R-1", 1, "A", "C", List.of(missingEvidence, verified));
        assertFalse(route.isOptimizationVerified());

        RouteDefinition completeRoute = new RouteDefinition(
                "R-2", 1, "A", "C", List.of(segment("A", "B", "https://traffic.example/permit"), verified));
        assertTrue(completeRoute.isOptimizationVerified());
    }

    private RouteSegment segment(String from, String to, String evidenceReference) {
        return new RouteSegment(from + to, 1, from, to, from + "-" + to, "HIGHWAY",
                120, 90, "AMAP_TRUCK", "request-" + from + to,
                RouteSegment.TruckAccessStatus.VERIFIED,
                evidenceReference == null ? "" : "TRANSPORT_AUTHORITY",
                evidenceReference,
                evidenceReference == null ? null : Instant.parse("2026-10-01T00:00:00Z"));
    }
}
