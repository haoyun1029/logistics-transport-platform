package cn.logistics.platform.optimization;

import cn.logistics.platform.domain.RouteSegment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreedyRouteBuilderTest {

    @Test
    void buildsContinuousCandidateFromObservedVerifiedEdgesAndSkipsUnverifiedShortcuts() {
        List<RouteSegment> observed = List.of(
                segment("AB", "A", "B", 120, 100, true),
                segment("AC", "A", "C", 65, 50, true),
                segment("CB", "C", "B", 35, 50, true),
                segment("BD", "B", "D", 40, 60, true),
                segment("AD", "A", "D", 30, 40, false));

        var result = GreedyRouteBuilder.build("candidate", "A", "D", observed,
                24, ScoringWeights.defaults()).orElseThrow();

        assertEquals(List.of("A", "C", "B", "D"), result.route().citySequence());
        assertTrue(result.route().isOptimizationVerified());
        assertEquals(3, result.selectionReasons().size());
        assertTrue(result.route().segments().stream().noneMatch(edge -> edge.id().equals("AD")));
    }

    @Test
    void pendingSegmentsAreSelectableOnlyWhenExplicitlyBuildingDemoCandidate() {
        List<RouteSegment> pending = List.of(
                segment("AC", "A", "C", 45, 45, false),
                segment("CB", "C", "B", 45, 45, false));

        assertTrue(GreedyRouteBuilder.build("strict", "A", "B", pending,
                24, ScoringWeights.defaults()).isEmpty());
        var result = GreedyRouteBuilder.build("demo", "A", "B", pending,
                24, ScoringWeights.defaults(), true).orElseThrow();

        assertTrue(result.demoOnly());
        assertEquals(List.of("A", "C", "B"), result.route().citySequence());
        assertTrue(result.route().isOptimizationReady(true));
        assertTrue(result.route().isOptimizationReady(false) == false);
        assertTrue(result.selectionReasons().stream().allMatch(reason -> reason.startsWith("演示路线：")));
    }

    private RouteSegment segment(String id, String from, String to, double minutes, double km, boolean verified) {
        return new RouteSegment(id, 1, from, to, id + " highway", "HIGHWAY", km, minutes,
                "AMAP_TRUCK", "request-" + id,
                verified ? RouteSegment.TruckAccessStatus.VERIFIED : RouteSegment.TruckAccessStatus.PENDING,
                verified ? "TRANSPORT_AUTHORITY" : "",
                verified ? "https://traffic.example/" + id : "",
                verified ? Instant.parse("2026-10-01T00:00:00Z") : null);
    }
}
