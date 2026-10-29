package cn.logistics.platform.domain;

import java.time.Instant;

public record TripSample(
        String taskId,
        String originCity,
        String destinationCity,
        boolean completed,
        Instant startedAt,
        Instant completedAt,
        double actualDistanceKm,
        double actualFuelLiters,
        boolean routeDifferenceRecoverable,
        boolean matchedTrajectoryIsSimulated) {

    public boolean isValidForOptimization() {
        return completed
                && FeedbackPlausibility.exclusionReason(startedAt, completedAt, actualDistanceKm, actualFuelLiters) == null
                && routeDifferenceRecoverable;
    }

    public String exclusionReason() {
        if (isValidForOptimization()) return "";
        if (!completed) return "任务未完成";
        if (startedAt == null || completedAt == null || !completedAt.isAfter(startedAt)) return "任务起止时间无效";
        if (!positiveFinite(actualDistanceKm)) return "实际里程无效";
        if (!positiveFinite(actualFuelLiters)) return "实际油耗无效";
        String feedbackReason = FeedbackPlausibility.exclusionReason(startedAt, completedAt, actualDistanceKm, actualFuelLiters);
        if (feedbackReason != null) return feedbackReason;
        if (!routeDifferenceRecoverable) return "轨迹匹配不足以可靠还原路线差异";
        return "样本不满足优化条件";
    }

    private static boolean positiveFinite(double value) {
        return value > 0 && Double.isFinite(value);
    }
}
