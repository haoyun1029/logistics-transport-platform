package cn.logistics.platform.optimization;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteComparisonTest {

    @Test
    void identifiesDivergenceAndRejoinCitySequences() {
        RouteDifference difference = RouteComparison.compareCitySequences(
                List.of("A", "B", "C", "D"), List.of("A", "X", "C", "D"));

        assertTrue(difference.different());
        assertEquals(1, difference.firstDivergenceIndex());
        assertEquals(2, difference.planRejoinIndex());
        assertEquals(List.of("B"), difference.planOnlyCities());
        assertEquals(List.of("X"), difference.actualOnlyCities());
    }

    @Test
    void preventsComparisonsAcrossDifferentOd() {
        assertThrows(IllegalArgumentException.class, () -> RouteComparison.compareCitySequences(
                List.of("A", "B"), List.of("A", "C")));
    }
}
