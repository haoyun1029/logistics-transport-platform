package cn.logistics.platform.service;

import cn.logistics.platform.domain.FeedbackPlausibility;
import cn.logistics.platform.domain.RoadGeometry;
import cn.logistics.platform.optimization.*;
import cn.logistics.platform.security.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskMatchingService {
  public static final String VERSION = "VERIFIED_GEOMETRY_V1";
  private final TransportBusinessService business;
  private final RouteManagementService routes;
  private final MongoTelemetryService mongo;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final SystemPolicyService policies;

  public TaskMatchingService(
      TransportBusinessService business,
      RouteManagementService routes,
      MongoTelemetryService mongo,
      JdbcTemplate jdbc,
      ObjectMapper json,
      SystemPolicyService policies) {
    this.business = business;
    this.routes = routes;
    this.mongo = mongo;
    this.jdbc = jdbc;
    this.json = json;
    this.policies = policies;
  }

  public JsonNode latest(long taskId, UserPrincipal actor) {
    business.task(taskId, actor);
    var values =
        jdbc.queryForList(
            "SELECT snapshot_json FROM actual_route_analyses WHERE task_id=? ORDER BY id DESC LIMIT"
                + " 1",
            String.class,
            taskId);
    return values.isEmpty()
        ? json.createObjectNode().put("sampleStatus", "PENDING_MATCH")
        : read(values.getFirst());
  }

  @Transactional
  public JsonNode analyse(long taskId, UserPrincipal actor) {
    if (actor.role() == UserRole.DISPATCHER) throw new ForbiddenException("调度员不能确认路线样本有效性。");
    jdbc.queryForList("SELECT id FROM transport_tasks WHERE id=? FOR UPDATE", Long.class, taskId);
    var task = business.task(taskId, actor);
    if (!"COMPLETED".equals(task.status())) throw new IllegalStateException("完成运输和实际反馈后才能复核路线样本。");
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("matcherVersion", VERSION);
    snapshot.put("source", "FLINK_MONGODB");
    snapshot.put("taskId", taskId);
    snapshot.put("taskCode", task.code());
    snapshot.put("originCity", task.originCity());
    snapshot.put("destinationCity", task.destinationCity());
    snapshot.put("sourceType", task.sourceType());
    snapshot.put("demoMode", task.demoMode());
    snapshot.put("formalOptimizationEligible", !task.demoMode());
    var policy = policies.current();
    snapshot.put("policy", policy);
    String reason = null;
    RouteManagementService.RouteView matched = null;
    snapshot.put("feedbackValidationVersion", FeedbackPlausibility.VERSION);
    snapshot.put("maximumPlausibleAverageSpeedKmh", FeedbackPlausibility.MAX_AVERAGE_SPEED_KMH);
    reason =
        FeedbackPlausibility.exclusionReason(
            task.startedAt(), task.completedAt(), task.actualDistanceKm(), task.actualFuelLiters());
    try {
      business.requireSimulationRoute(task.routeId(), task.demoMode());
    } catch (IllegalStateException e) {
      reason = "计划路线未完成逐段核验，不能将该样本计为优化有效。";
    }
    if (reason == null) {
      var trace = mongo.trajectory(task.code());
      if (!"CONNECTED".equals(trace.status()))
        throw new IllegalStateException("轨迹服务不可用，保留现有样本结论，未写入新的匹配结果。");
      var latest =
          trace.points().stream()
              .filter(
                  p ->
                      p.totalPointCount() != null
                          && p.runId() != null
                          && "WGS84".equals(p.coordinateSystem()))
              .max(Comparator.comparing(MongoTelemetryService.MapPoint::processedAt));
      if (latest.isEmpty()) reason = "没有带完整运行编号及显式坐标系的道路模拟轨迹；旧城市插值演示不参与优化。";
      else {
        String runId = latest.get().runId();
        var points =
            trace.points().stream()
                .filter(p -> runId.equals(p.runId()))
                .sorted(Comparator.comparingInt(MongoTelemetryService.MapPoint::sequence))
                .toList();
        int total = latest.get().totalPointCount();
        snapshot.put("runId", runId);
        snapshot.put(
            "eventIds", points.stream().map(MongoTelemetryService.MapPoint::eventId).toList());
        snapshot.put("observedPointCount", points.size());
        if (points.stream()
            .anyMatch(
                p ->
                    !Integer.valueOf(total).equals(p.totalPointCount())
                        || !"WGS84".equals(p.coordinateSystem()))) reason = "同一模拟运行的元数据不一致。";
        else {
          var eligible = new ArrayList<RouteManagementService.RouteView>();
          var paths = new ArrayList<RoadMatcher.Path>();
          for (var route : routes.routes())
            if (task.originCity().equals(route.originCity())
                && task.destinationCity().equals(route.destinationCity())
                && ("VERIFIED".equals(route.verificationStatus())
                    || (task.demoMode()
                        && "PENDING_VERIFICATION".equals(route.verificationStatus())))) {
              business.requireSimulationRoute(route.databaseId(), task.demoMode());
              eligible.add(route);
              var segments =
                  route.segments().stream()
                      .map(
                          s ->
                              new RoadMatcher.Segment(
                                  s.id(),
                                  RoadGeometry.wgs84(RoadGeometry.parse(s.geometryJson(), json))))
                      .toList();
              paths.add(
                  new RoadMatcher.Path(
                      route.databaseId(),
                      route.segments().stream()
                          .map(TaskMatchingService::roadKey)
                          .reduce("", (a, b) -> a + "|" + b),
                      segments));
            }
          var observations =
              points.stream()
                  .map(
                      p ->
                          new RoadMatcher.Observation(
                              p.sequence(), new RoadGeometry.Point(p.longitude(), p.latitude())))
                  .toList();
          var outcome = RoadMatcher.match(observations, total, paths, policy.matchingDistanceKm());
          snapshot.put("coverage", outcome);
          reason = outcome.valid() ? null : outcome.reason();
          if (outcome.valid()) {
            matched =
                eligible.stream()
                    .filter(r -> r.databaseId() == outcome.matchedRouteId())
                    .findFirst()
                    .orElseThrow();
            double roadDistance =
                matched.segments().stream()
                    .mapToDouble(RouteManagementService.SegmentView::distanceKm)
                    .sum();
            if (task.actualDistanceKm() < roadDistance * .5
                || task.actualDistanceKm() > roadDistance * 1.5)
              reason = "实际里程与已匹配道路估算相差过大，保留历史并排除优化。";
            snapshot.put("matchedRoute", matched);
            snapshot.put(
                "observedSegmentIds",
                matched.segments().stream().map(RouteManagementService.SegmentView::id).toList());
            var plan = routes.route(task.routeId());
            snapshot.put(
                "cityDifference",
                RouteComparison.compareCitySequences(plan.citySequence(), matched.citySequence()));
            snapshot.put(
                "planRoadSequence",
                plan.segments().stream().map(TaskMatchingService::roadKey).toList());
            snapshot.put(
                "actualRoadSequence",
                matched.segments().stream().map(TaskMatchingService::roadKey).toList());
          }
        }
      }
    }
    boolean valid = reason == null;
    snapshot.put("sampleStatus", valid ? "VALID" : "INVALID");
    snapshot.put(
        "reason",
        valid
            ? (task.demoMode()
                ? "DEMO_MODE - 已从模拟运行还原道路/城市序列；该样本仅用于演示，不计入正式优化。"
                : "已从完整模拟运行可靠还原实际道路/城市序列；实测指标单独用于校准。")
            : reason);
    snapshot.put("actualDistanceKm", task.actualDistanceKm());
    snapshot.put("actualFuelLiters", task.actualFuelLiters());
    snapshot.put(
        "actualElapsedMinutes",
        task.startedAt() == null || task.completedAt() == null
            ? null
            : (task.completedAt().toEpochMilli() - task.startedAt().toEpochMilli()) / 60000.0);
    snapshot.put("vehicleId", task.vehicleId());
    if (task.vehicleId() != null)
      snapshot.put(
          "vehicleFuelModelLitersPer100Km",
          jdbc.queryForObject(
              "SELECT fuel_model_liters_per_100km FROM vehicles WHERE id=?",
              Double.class,
              task.vehicleId()));
    String value = write(snapshot);
    var key = new GeneratedKeyHolder();
    jdbc.update(
        c -> {
          var s =
              c.prepareStatement(
                  "INSERT INTO"
                      + " actual_route_analyses(task_id,sample_status,snapshot_json,created_by)"
                      + " VALUES(?,?,?,?)",
                  new String[] {"id"});
          s.setLong(1, taskId);
          s.setString(2, valid ? "VALID" : "INVALID");
          s.setString(3, value);
          s.setLong(4, actor.id());
          return s;
        },
        key);
    jdbc.update(
        "UPDATE transport_tasks SET"
            + " sample_status=?,exclusion_reason=?,matched_route_json=?,matched_city_sequence=?,route_difference_recoverable=?"
            + " WHERE id=?",
        valid ? "VALID" : "INVALID",
        reason,
        value,
        matched == null ? null : write(matched.citySequence()),
        valid,
        taskId);
    business.audit(
        actor.id(),
        "TASK_ROUTE_ANALYSED",
        task.code(),
        Map.of(
            "analysisId",
            Objects.requireNonNull(key.getKey()).longValue(),
            "sampleStatus",
            valid ? "VALID" : "INVALID"));
    return read(value);
  }

  public static String roadKey(RouteManagementService.SegmentView s) {
    try {
      return s.fromCity()
          + "→"
          + s.toCity()
          + "/"
          + s.roadName()
          + "/"
          + s.mapSource()
          + "/"
          + HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(s.geometryJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode read(String value) {
    try {
      return json.readTree(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
