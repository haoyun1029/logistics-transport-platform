package cn.logistics.platform.optimization;

import cn.logistics.platform.domain.RoadGeometry;
import java.util.*;

/**
 * Conservative geometric matching against complete, independently verified route versions.
 * Sparse/ambiguous traces are rejected; nearest-point proximity alone never proves a road
 * traversal.
 */
public final class RoadMatcher {
  private RoadMatcher() {}

  public record Segment(long id, List<RoadGeometry.Point> points) {}

  public record Path(long routeId, String signature, List<Segment> segments) {}

  public record Observation(int sequence, RoadGeometry.Point point) {}

  public record Outcome(
      boolean valid,
      String reason,
      Long matchedRouteId,
      int missingPoints,
      double matchedFraction) {}

  private record Edge(
      RoadGeometry.Point a, RoadGeometry.Point b, double start, double length, int segment) {}

  private record Projection(double distance, double progress, int segment) {}

  public static Outcome match(
      List<Observation> observations, int totalPoints, List<Path> paths, double thresholdKm) {
    if (totalPoints < 4
        || observations.size() < 4
        || observations.getFirst().sequence() != 1
        || observations.getLast().sequence() != totalPoints)
      return new Outcome(false, "缺少完整运行的起终点或点数不足。", null, 0, 0);
    int missing = totalPoints - observations.size(), previous = 0;
    if (missing < 0 || missing / (double) totalPoints > .1)
      return new Outcome(false, "轨迹缺口超过 10%，无法可靠还原路线差异。", null, missing, 0);
    for (var p : observations) {
      if (p.sequence() <= previous || p.sequence() - previous > 3)
        return new Outcome(false, "轨迹乱序、重复或连续缺点过多。", null, missing, 0);
      previous = p.sequence();
    }
    var matches = new LinkedHashMap<String, Path>();
    double bestFraction = 0;
    for (var path : paths) {
      var edges = new ArrayList<Edge>();
      double length = 0;
      for (int s = 0; s < path.segments().size(); s++) {
        var points = path.segments().get(s).points();
        for (int i = 1; i < points.size(); i++) {
          double len = RoadGeometry.distanceKm(points.get(i - 1), points.get(i));
          if (len > .000001) {
            edges.add(new Edge(points.get(i - 1), points.get(i), length, len, s));
            length += len;
          }
        }
      }
      if (edges.isEmpty()) continue;
      int[] counts = new int[path.segments().size()];
      int accepted = 0, unmatchedStreak = 0;
      double last = -1, first = -1;
      boolean order = true;
      for (var observation : observations) {
        var closest = project(observation.point(), edges);
        if (closest.distance() > thresholdKm) {
          if (++unmatchedStreak > 2) order = false;
          continue;
        }
        unmatchedStreak = 0;
        if (last >= 0 && (closest.progress() < last - .05 || closest.progress() - last > 5))
          order = false;
        if (first < 0) first = closest.progress();
        last = Math.max(last, closest.progress());
        counts[closest.segment()]++;
        accepted++;
      }
      double fraction = accepted / (double) totalPoints;
      bestFraction = Math.max(bestFraction, fraction);
      boolean covered = Arrays.stream(counts).allMatch(n -> n >= 2);
      if (order && covered && fraction >= .9 && first <= .3 && length - last <= .3)
        matches.putIfAbsent(path.signature(), path);
    }
    if (matches.size() != 1)
      return new Outcome(
          false,
          matches.isEmpty() ? "没有一条已核验路线被连续覆盖；可能存在道路缺口、回退或观测不足。" : "轨迹同时符合多条不同道路走法，无法可靠区分，已排除优化。",
          null,
          missing,
          bestFraction);
    return new Outcome(
        true,
        "完整模拟运行与唯一已核验道路序列匹配；单段至少两个观测，连续距离缺口不超过 5 km。",
        matches.values().iterator().next().routeId(),
        missing,
        bestFraction);
  }

  private static Projection project(RoadGeometry.Point point, List<Edge> edges) {
    double best = Double.POSITIVE_INFINITY, progress = 0;
    int segment = 0;
    double cos = Math.cos(Math.toRadians(point.latitude()));
    for (var e : edges) {
      double ax = (e.a().longitude() - point.longitude()) * 111.195 * cos,
          ay = (e.a().latitude() - point.latitude()) * 111.195;
      double bx = (e.b().longitude() - point.longitude()) * 111.195 * cos,
          by = (e.b().latitude() - point.latitude()) * 111.195;
      double dx = bx - ax, dy = by - ay, den = dx * dx + dy * dy;
      double t = den == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / den));
      double distance = Math.hypot(ax + t * dx, ay + t * dy);
      if (distance < best) {
        best = distance;
        progress = e.start() + t * e.length();
        segment = e.segment();
      }
    }
    return new Projection(best, progress, segment);
  }
}
