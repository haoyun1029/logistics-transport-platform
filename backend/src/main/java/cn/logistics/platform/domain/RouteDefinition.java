package cn.logistics.platform.domain;

import java.util.List;

public record RouteDefinition(
        String id,
        int version,
        String originCity,
        String destinationCity,
        List<RouteSegment> segments) {

    public RouteDefinition {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public List<String> citySequence() {
        if (segments.isEmpty()) return List.of(originCity, destinationCity);
        var cities = new java.util.ArrayList<String>();
        cities.add(segments.getFirst().fromCity());
        for (RouteSegment segment : segments) cities.add(segment.toCity());
        return List.copyOf(cities);
    }

    public boolean isOptimizationVerified() {
        return isOptimizationReady(false);
    }

    public boolean isOptimizationReady(boolean allowPendingDemo) {
        if (segments.isEmpty() || originCity == null || destinationCity == null) return false;
        String currentCity = originCity;
        for (int i = 0; i < segments.size(); i++) {
            RouteSegment segment = segments.get(i);
            if (segment.sequence() != i + 1
                    || !segment.connectsFrom(currentCity)
                    || !segment.isOptimizationReady(allowPendingDemo)) return false;
            currentCity = segment.toCity();
        }
        return destinationCity.equals(currentCity);
    }
}
