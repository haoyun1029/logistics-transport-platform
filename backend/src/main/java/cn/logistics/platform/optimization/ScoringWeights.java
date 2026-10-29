package cn.logistics.platform.optimization;

public record ScoringWeights(double travelTime, double distance, double fuel) {

    public static ScoringWeights defaults() {
        return new ScoringWeights(0.40, 0.30, 0.30);
    }

    public void validate() {
        if (!validUnit(travelTime) || !validUnit(distance) || !validUnit(fuel)
                || Math.abs((travelTime + distance + fuel) - 1.0) > 1.0e-9) {
            throw new IllegalArgumentException("评分权重必须为非负数且合计 100% 。");
        }
    }

    private static boolean validUnit(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }
}
