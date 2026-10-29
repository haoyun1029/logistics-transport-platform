package cn.logistics.platform.optimization;

import cn.logistics.platform.domain.RouteDefinition;
import cn.logistics.platform.domain.RouteSegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Greedy local route construction; pending segments are available only in explicit demo mode. */
public final class GreedyRouteBuilder {

  private GreedyRouteBuilder() {}

  public record Candidate(RouteDefinition route, List<String> selectionReasons, boolean demoOnly) {
    public Candidate {
      selectionReasons = List.copyOf(selectionReasons);
    }
  }

  public static Optional<Candidate> build(
      String candidateId,
      String origin,
      String destination,
      List<RouteSegment> observedSegments,
      double litersPer100Km,
      ScoringWeights weights) {
    return build(
        candidateId, origin, destination, observedSegments, litersPer100Km, weights, false);
  }

  public static Optional<Candidate> build(
      String candidateId,
      String origin,
      String destination,
      List<RouteSegment> observedSegments,
      double litersPer100Km,
      ScoringWeights weights,
      boolean allowPendingDemo) {
    if (origin == null
        || destination == null
        || origin.isBlank()
        || destination.isBlank()
        || origin.equals(destination)
        || !Double.isFinite(litersPer100Km)
        || litersPer100Km <= 0) {
      throw new IllegalArgumentException("候选路线起终点或油耗估算参数无效。");
    }
    weights.validate();
    List<RouteSegment> eligible =
        observedSegments.stream()
            .filter(
                segment ->
                    segment.isOptimizationVerified()
                        || (allowPendingDemo && isDemoEligible(segment)))
            .filter(segment -> segment.mapSource() != null && !segment.mapSource().isBlank())
            .toList();
    if (eligible.isEmpty()) return Optional.empty();

    List<RouteSegment> selected = new ArrayList<>();
    List<String> reasons = new ArrayList<>();
    Set<String> visitedCities = new HashSet<>();
    Set<String> usedSegments = new HashSet<>();
    visitedCities.add(origin);
    String current = origin;

    for (int step = 0; step < eligible.size() && !current.equals(destination); step++) {
      final String from = current;
      List<RouteSegment> outgoing =
          eligible.stream()
              .filter(segment -> segment.connectsFrom(from))
              .filter(segment -> !usedSegments.contains(segment.id()))
              .filter(
                  segment ->
                      segment.toCity().equals(destination)
                          || !visitedCities.contains(segment.toCity()))
              .toList();
      if (outgoing.isEmpty()) return Optional.empty();

      List<RouteMetrics> localMetrics =
          outgoing.stream()
              .map(
                  segment ->
                      new RouteMetrics(
                          segment.id(),
                          origin,
                          destination,
                          segment.estimatedMinutes(),
                          segment.distanceKm(),
                          segment.distanceKm() * litersPer100Km / 100.0,
                          allowPendingDemo
                              ? "OBSERVED_MAP_PLUS_CALIBRATED_FUEL_DEMO_ONLY"
                              : "OBSERVED_VERIFIED_MAP_PLUS_CALIBRATED_FUEL"))
              .toList();
      List<RouteScore> localScores = RouteScoreCalculator.scoreSameOd(localMetrics, weights);
      RouteScore selectedScore =
          localScores.stream()
              .max(
                  Comparator.comparingDouble(RouteScore::compositeScore)
                      .thenComparing(RouteScore::routeId, Comparator.reverseOrder()))
              .orElseThrow();
      RouteSegment segment =
          outgoing.stream()
              .filter(option -> option.id().equals(selectedScore.routeId()))
              .findFirst()
              .orElseThrow();
      selected.add(segment.withSequence(selected.size() + 1));
      usedSegments.add(segment.id());
      visitedCities.add(segment.toCity());
      reasons.add(
          (allowPendingDemo ? "演示路线：从已观测道路中选择 " : "从已观测且逐段核验的连续路段中选择 ")
              + segment.fromCity()
              + " → "
              + segment.toCity()
              + "（局部综合分 "
              + String.format(java.util.Locale.ROOT, "%.2f", selectedScore.compositeScore())
              + "）。");
      current = segment.toCity();
    }
    if (!current.equals(destination)) return Optional.empty();

    RouteDefinition route = new RouteDefinition(candidateId, 1, origin, destination, selected);
    if (!route.isOptimizationReady(allowPendingDemo)) return Optional.empty();
    return Optional.of(new Candidate(route, reasons, allowPendingDemo));
  }

  private static boolean isDemoEligible(RouteSegment segment) {
    return segment.truckAccessStatus() == RouteSegment.TruckAccessStatus.PENDING
        && segment.roadName() != null
        && !segment.roadName().isBlank()
        && segment.mapSource() != null
        && !segment.mapSource().isBlank()
        && segment.mapRequestReference() != null
        && !segment.mapRequestReference().isBlank()
        && Double.isFinite(segment.distanceKm())
        && segment.distanceKm() > 0
        && Double.isFinite(segment.estimatedMinutes())
        && segment.estimatedMinutes() > 0;
  }
}
