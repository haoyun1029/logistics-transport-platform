package cn.logistics.platform.optimization;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouteScoreCalculatorTest {

    @Test
    void usesSameOdMinMaxBoundsAndFiftyForEqualMetrics() {
        List<RouteScore> scores = RouteScoreCalculator.scoreSameOd(List.of(
                metrics("planned", "A", "B", 100, 100, 50),
                metrics("candidate", "A", "B", 80, 100, 40)), ScoringWeights.defaults());

        assertEquals(0, scores.getFirst().travelTimeScore());
        assertEquals(100, scores.getLast().travelTimeScore());
        assertEquals(50, scores.getFirst().distanceScore());
        assertEquals(50, scores.getLast().distanceScore());
        assertEquals(15, scores.getFirst().compositeScore(), 1.0e-9);
        assertEquals(85, scores.getLast().compositeScore(), 1.0e-9);
        assertEquals(100, scores.getFirst().bounds().distance().minimum());
        assertEquals(100, scores.getFirst().bounds().distance().maximum());
    }

    @Test
    void rejectsDifferentOdAndDifferentEstimateSources() {
        assertThrows(IllegalArgumentException.class, () -> RouteScoreCalculator.scoreSameOd(List.of(
                metrics("planned", "A", "B", 100, 100, 50),
                metrics("candidate", "A", "C", 80, 120, 40)), ScoringWeights.defaults()));
        assertThrows(IllegalArgumentException.class, () -> RouteScoreCalculator.scoreSameOd(List.of(
                metrics("planned", "A", "B", 100, 100, 50),
                new RouteMetrics("candidate", "A", "B", 80, 120, 40, "ANOTHER_SOURCE")), ScoringWeights.defaults()));
    }

    @Test
    void validatesConfiguredWeightTotal() {
        assertThrows(IllegalArgumentException.class,
                () -> RouteScoreCalculator.scoreSameOd(List.of(metrics("one", "A", "B", 1, 1, 1)),
                        new ScoringWeights(0.4, 0.4, 0.3)));
    }

    private RouteMetrics metrics(String id, String from, String to, double minutes, double km, double fuel) {
        return new RouteMetrics(id, from, to, minutes, km, fuel, "MAP_PLUS_CALIBRATED_FUEL_V1");
    }
}
