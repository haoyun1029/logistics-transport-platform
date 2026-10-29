package cn.logistics.platform.domain;

import java.time.Instant;
import java.util.Objects;

/** One ordered, adjacent-city road segment. Truck verification is evidence-backed. */
public record RouteSegment(
        String id,
        int sequence,
        String fromCity,
        String toCity,
        String roadName,
        String roadType,
        double distanceKm,
        double estimatedMinutes,
        String mapSource,
        String mapRequestReference,
        TruckAccessStatus truckAccessStatus,
        String truckEvidenceSource,
        String truckEvidenceReference,
        Instant verifiedAt) {

    public enum TruckAccessStatus { PENDING, VERIFIED, REJECTED }

    public boolean isOptimizationVerified() {
        return isOptimizationReady(false);
    }

    public boolean isOptimizationReady(boolean allowPendingDemo) {
        boolean evidenceBacked = truckAccessStatus == TruckAccessStatus.VERIFIED
                && hasText(truckEvidenceSource)
                && hasText(truckEvidenceReference)
                && verifiedAt != null;
        boolean demoPending = allowPendingDemo && truckAccessStatus == TruckAccessStatus.PENDING;
        return (evidenceBacked || demoPending)
                && hasText(roadName)
                && hasText(mapSource)
                && hasText(mapRequestReference)
                && distanceKm > 0
                && Double.isFinite(distanceKm)
                && estimatedMinutes > 0
                && Double.isFinite(estimatedMinutes);
    }

    public boolean connectsFrom(String city) {
        return Objects.equals(fromCity, city);
    }

    public RouteSegment withSequence(int nextSequence) {
        return new RouteSegment(id, nextSequence, fromCity, toCity, roadName, roadType,
                distanceKm, estimatedMinutes, mapSource, mapRequestReference,
                truckAccessStatus, truckEvidenceSource, truckEvidenceReference, verifiedAt);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
