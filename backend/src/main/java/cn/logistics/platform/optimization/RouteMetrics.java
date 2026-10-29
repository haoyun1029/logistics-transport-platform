package cn.logistics.platform.optimization;

public record RouteMetrics(
        String routeId,
        String originCity,
        String destinationCity,
        double estimatedMinutes,
        double estimatedDistanceKm,
        double estimatedFuelLiters,
        String estimateSource) {

    public void validate() {
        if (blank(routeId) || blank(originCity) || blank(destinationCity)
                || blank(estimateSource)
                || !positiveFinite(estimatedMinutes)
                || !positiveFinite(estimatedDistanceKm)
                || !positiveFinite(estimatedFuelLiters)) {
            throw new IllegalArgumentException("路线估算指标不完整或无效。");
        }
    }

    private static boolean positiveFinite(double value) {
        return value > 0 && Double.isFinite(value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
