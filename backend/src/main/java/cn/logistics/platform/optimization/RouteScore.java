package cn.logistics.platform.optimization;

public record RouteScore(
        String routeId,
        double travelTimeScore,
        double distanceScore,
        double fuelScore,
        double compositeScore,
        NormalizationBounds bounds,
        ScoringWeights weights,
        String estimateSource) {

    public record MetricBounds(double minimum, double maximum) {}

    public record NormalizationBounds(
            MetricBounds travelTime,
            MetricBounds distance,
            MetricBounds fuel) {}
}
