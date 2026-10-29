package cn.logistics.platform.optimization;

import java.util.List;

public record RouteDifference(
        int firstDivergenceIndex,
        int planRejoinIndex,
        List<String> planOnlyCities,
        List<String> actualOnlyCities,
        boolean different) {

    public RouteDifference {
        planOnlyCities = List.copyOf(planOnlyCities);
        actualOnlyCities = List.copyOf(actualOnlyCities);
    }
}
