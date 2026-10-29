package cn.logistics.platform.optimization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Min-Max comparison is scoped to one OD and uses estimated values only. */
public final class RouteScoreCalculator {

    private RouteScoreCalculator() {}

    public static List<RouteScore> scoreSameOd(List<RouteMetrics> candidates, ScoringWeights weights) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("至少需要计划路线和候选路线才能评分。");
        }
        Objects.requireNonNull(weights, "weights").validate();
        candidates.forEach(RouteMetrics::validate);
        RouteMetrics first = candidates.getFirst();
        String sharedEstimateSource = first.estimateSource();
        for (RouteMetrics item : candidates) {
            if (!first.originCity().equals(item.originCity())
                    || !first.destinationCity().equals(item.destinationCity())) {
                throw new IllegalArgumentException("不同 OD 的路线不能放在同一轮评分中。");
            }
            if (!sharedEstimateSource.equals(item.estimateSource())) {
                throw new IllegalArgumentException("同一轮评分必须使用同一来源的估算指标。");
            }
        }

        RouteScore.NormalizationBounds bounds = new RouteScore.NormalizationBounds(
                bounds(candidates, RouteMetrics::estimatedMinutes),
                bounds(candidates, RouteMetrics::estimatedDistanceKm),
                bounds(candidates, RouteMetrics::estimatedFuelLiters));

        List<RouteScore> scores = new ArrayList<>();
        for (RouteMetrics candidate : candidates) {
            double time = lowerIsBetter(candidate.estimatedMinutes(), bounds.travelTime());
            double distance = lowerIsBetter(candidate.estimatedDistanceKm(), bounds.distance());
            double fuel = lowerIsBetter(candidate.estimatedFuelLiters(), bounds.fuel());
            scores.add(new RouteScore(
                    candidate.routeId(),
                    time,
                    distance,
                    fuel,
                    time * weights.travelTime() + distance * weights.distance() + fuel * weights.fuel(),
                    bounds,
                    weights,
                    sharedEstimateSource));
        }
        return List.copyOf(scores);
    }

    private static RouteScore.MetricBounds bounds(
            List<RouteMetrics> items,
            java.util.function.ToDoubleFunction<RouteMetrics> value) {
        double min = items.stream().mapToDouble(value).min().orElseThrow();
        double max = items.stream().mapToDouble(value).max().orElseThrow();
        return new RouteScore.MetricBounds(min, max);
    }

    private static double lowerIsBetter(double value, RouteScore.MetricBounds bounds) {
        if (Double.compare(bounds.maximum(), bounds.minimum()) == 0) return 50.0;
        return (bounds.maximum() - value) / (bounds.maximum() - bounds.minimum()) * 100.0;
    }
}
