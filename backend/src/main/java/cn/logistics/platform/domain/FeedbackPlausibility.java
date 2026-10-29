package cn.logistics.platform.domain;

import java.time.Instant;

/** A conservative data-quality ceiling, not a road speed limit or transport-time estimate. */
public final class FeedbackPlausibility {
    public static final String VERSION = "FEEDBACK_PLAUSIBILITY_V1";
    public static final double MAX_AVERAGE_SPEED_KMH = 150;
    public static final double MAX_LITERS_PER_KM = 2;
    private FeedbackPlausibility() {}

    public static String exclusionReason(Instant start, Instant end, Double distance, Double fuel) {
        return exclusionReason(start == null || end == null ? null
                : (end.toEpochMilli() - start.toEpochMilli()) / 60000.0, distance, fuel);
    }

    public static String exclusionReason(Double minutes, Double distance, Double fuel) {
        if (!positive(minutes)) return "任务起止时间无效";
        if (!positive(distance)) return "实际里程无效";
        if (!positive(fuel)) return "实际油耗无效";
        if (fuel / distance > MAX_LITERS_PER_KM) return "油耗与里程比例异常，保留反馈并排除优化。";
        if (distance * 60 > MAX_AVERAGE_SPEED_KMH * minutes)
            return "实际里程与系统运输耗时不合理（平均速度超过 150 km/h），保留反馈并排除优化；加速模拟不替代实际耗时。";
        return null;
    }

    private static boolean positive(Double value) {
        return value != null && Double.isFinite(value) && value > 0;
    }
}
