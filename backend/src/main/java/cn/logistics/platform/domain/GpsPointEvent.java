package cn.logistics.platform.domain;

import java.time.Instant;

/** Raw GPS payload published before map matching. It intentionally has no city or road fields. */
public record GpsPointEvent(
        String taskId,
        String vehicleId,
        String routeId,
        Instant timestamp,
        double latitude,
        double longitude,
        double speedKmh,
        String sourceType,
        boolean demoMode,
        int sequence) {

    public GpsPointEvent {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId 不能为空。");
        if (vehicleId == null || vehicleId.isBlank()) throw new IllegalArgumentException("vehicleId 不能为空。");
        if (routeId == null || routeId.isBlank()) throw new IllegalArgumentException("routeId 不能为空。");
        if (timestamp == null) throw new IllegalArgumentException("timestamp 不能为空。");
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude 必须是有效坐标。");
        }
        if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude 必须是有效坐标。");
        }
        if (!Double.isFinite(speedKmh) || speedKmh < 0) throw new IllegalArgumentException("speedKmh 不能为负数。");
        if (!"SIMULATED".equals(sourceType)) throw new IllegalArgumentException("sourceType 必须明确标记为 SIMULATED。");
        if (sequence < 1) throw new IllegalArgumentException("sequence 必须从 1 开始。");
    }
}
