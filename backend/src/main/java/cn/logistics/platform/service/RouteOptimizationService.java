package cn.logistics.platform.service;

import cn.logistics.platform.domain.*;
import cn.logistics.platform.optimization.*;
import cn.logistics.platform.security.*;
import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RouteOptimizationService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final TransportBusinessService business;
  private final RouteManagementService routes;
  private final SystemPolicyService policies;

  public RouteOptimizationService(
      JdbcTemplate jdbc,
      ObjectMapper json,
      TransportBusinessService business,
      RouteManagementService routes,
      SystemPolicyService policies) {
    this.jdbc = jdbc;
    this.json = json;
    this.business = business;
    this.routes = routes;
    this.policies = policies;
  }

  public record Evaluation(long id, Instant createdAt, JsonNode snapshot) {}

  public List<Evaluation> history() {
    return jdbc.query(
        "SELECT id,created_at,snapshot_json FROM optimization_evaluations ORDER BY id DESC LIMIT"
            + " 100",
        (rs, n) ->
            new Evaluation(
                rs.getLong("id"),
                rs.getTimestamp("created_at").toInstant(),
                read(rs.getString("snapshot_json"))));
  }

  @Transactional
  public Evaluation evaluate(long planId, UserPrincipal actor) {
    if (actor.role() != UserRole.ADMIN
        && actor.role() != UserRole.SUPER_ADMIN
        && actor.role() != UserRole.DISPATCHER)
      throw new ForbiddenException("当前账号不能评估优化候选。");
    var plan = routes.route(planId);
    var policy = policies.current();
    var snapshot = new LinkedHashMap<String, Object>();
    boolean demoMode = business.demoModeEnabled();
    snapshot.put("plan", plan);
    snapshot.put("weights", policy.weights());
    snapshot.put("policyVersion", policy.version());
    snapshot.put("sourceType", "SIMULATED");
    snapshot.put("demoMode", demoMode);
    snapshot.put(
        "algorithm", demoMode ? "OBSERVED_ROUTE_GREEDY_DEMO_V1" : "OBSERVED_VERIFIED_GREEDY_V1");
    snapshot.put("validSampleCount", 0);
    snapshot.put("scores", List.of());
    snapshot.put(
        "limitation",
        demoMode
            ? "DEMO_MODE - 演示环境：允许待核验道路用于技术流程验证；不代表货车通行已核验，不生成正式推荐。"
            : "仅在同一 OD 的已观测且已核验道路上做局部贪心选择；模拟数据、单次运输和初步推荐均不证明全局最优或现实普遍效果。");
    String demoFilter = demoMode ? "AND t.demo_mode=TRUE" : "AND t.demo_mode=FALSE";
    long completedTaskCount = jdbc.queryForObject(
        ("""
SELECT COUNT(*) FROM transport_tasks t JOIN routes r ON r.id=t.route_id
WHERE t.task_status='COMPLETED' AND r.origin_city=? AND r.destination_city=? %s
""").formatted(demoFilter), Long.class, plan.originCity(), plan.destinationCity());
    long taskValidFlagCount = jdbc.queryForObject(
        ("""
SELECT COUNT(*) FROM transport_tasks t JOIN routes r ON r.id=t.route_id
WHERE t.task_status='COMPLETED' AND t.sample_status='VALID'
  AND r.origin_city=? AND r.destination_city=? %s
""").formatted(demoFilter), Long.class, plan.originCity(), plan.destinationCity());
    long latestAnalysisCount = jdbc.queryForObject(
        ("""
SELECT COUNT(*) FROM actual_route_analyses a
JOIN transport_tasks t ON t.id=a.task_id
JOIN routes r ON r.id=t.route_id
WHERE a.id=(SELECT MAX(newest.id) FROM actual_route_analyses newest WHERE newest.task_id=a.task_id)
  AND t.task_status='COMPLETED' AND r.origin_city=? AND r.destination_city=? %s
""").formatted(demoFilter), Long.class, plan.originCity(), plan.destinationCity());
    snapshot.put("completedTaskCount", completedTaskCount);
    snapshot.put("taskValidFlagCount", taskValidFlagCount);
    snapshot.put("latestAnalysisCount", latestAnalysisCount);
    try {
      business.requireSimulationRoute(planId, demoMode);
    } catch (IllegalStateException e) {
      return save(planId, null, "NO_RECOMMENDATION", "计划路线尚未完成逐段货车核验和道路几何校验。", snapshot, actor);
    }
    var values =
        jdbc.queryForList(
            ("""
SELECT a.id,a.snapshot_json FROM actual_route_analyses a JOIN transport_tasks t ON t.id=a.task_id
JOIN routes r ON r.id=t.route_id
WHERE a.id=(SELECT MAX(newest.id) FROM actual_route_analyses newest WHERE newest.task_id=a.task_id)
  AND a.sample_status='VALID' AND t.sample_status='VALID' AND t.task_status='COMPLETED'
  AND t.route_difference_recoverable=TRUE AND r.origin_city=? AND r.destination_city=?
  %s
ORDER BY a.id
""")
                .formatted(demoFilter),
            plan.originCity(),
            plan.destinationCity());
    var samples = new ArrayList<JsonNode>();
    var analysisIds = new ArrayList<Long>();
    for (var row : values) {
      var s = read(row.get("snapshot_json").toString());
      if (TaskMatchingService.VERSION.equals(s.path("matcherVersion").asText())
          && "FLINK_MONGODB".equals(s.path("source").asText())
          && FeedbackPlausibility.exclusionReason(
                  s.path("actualElapsedMinutes").asDouble(),
                  s.path("actualDistanceKm").asDouble(),
                  s.path("actualFuelLiters").asDouble())
              == null) {
        samples.add(s);
        analysisIds.add(((Number) row.get("id")).longValue());
      }
    }
    snapshot.put("feedbackValidationVersion", FeedbackPlausibility.VERSION);
    snapshot.put("validSampleCount", demoMode ? 0 : samples.size());
    snapshot.put("demoSampleCount", demoMode ? samples.size() : 0);
    snapshot.put("analysisIds", analysisIds);
    snapshot.put("actualSamples", samples);
    snapshot.put("eligibleSampleCount", samples.size());
    if (samples.isEmpty())
      return save(
          planId,
          null,
          "NO_RECOMMENDATION",
          completedTaskCount == 0
              ? "同一 OD 尚无已完成运输任务；任务状态标签不能代替服务端道路匹配证据。"
              : latestAnalysisCount == 0
                  ? "同一 OD 有 " + completedTaskCount + " 条已完成任务（其中 " + taskValidFlagCount
                      + " 条状态标为有效），但没有最新道路匹配分析记录；先确认 GPS 已落库，再对任务执行轨迹匹配复核。"
                  : "同一 OD 有 " + latestAnalysisCount + " 条最新匹配分析记录，但没有记录通过当前匹配版本、反馈合理性和数据来源校验；任务状态标签不能代替评估证据。",
          snapshot,
          actor);
    Set<String> sources = new HashSet<>();
    plan.segments().forEach(s -> sources.add(s.mapSource()));
    if (sources.size() != 1)
      return save(planId, null, "NO_RECOMMENDATION", "计划路段估算来源不一致，不能进行同源评分。", snapshot, actor);
    String mapSource = sources.iterator().next();
    double liters = samples.stream().mapToDouble(s -> s.path("actualFuelLiters").asDouble()).sum(),
        kilometers = samples.stream().mapToDouble(s -> s.path("actualDistanceKm").asDouble()).sum(),
        rate = liters / kilometers * 100;
    String estimateSource = "MAP:" + mapSource + ";FUEL:OD_CALIBRATION_V1";
    snapshot.put("estimateSource", estimateSource);
    snapshot.put("calibratedLitersPer100Km", rate);
    snapshot.put("fuelValueType", "ESTIMATED");
    double modelFuel =
        samples.stream()
            .mapToDouble(
                s ->
                    s.path("actualDistanceKm").asDouble()
                        * s.path("vehicleFuelModelLitersPer100Km").asDouble()
                        / 100)
            .sum();
    snapshot.put("vehicleModelEstimatedFuelForCalibration", modelFuel);
    snapshot.put("observedFuelForCalibration", liters);
    snapshot.put("vehicleModelCalibrationFactor", modelFuel > 0 ? liters / modelFuel : null);
    var pool = new LinkedHashMap<String, RouteManagementService.SegmentView>();
    for (var sample : samples) {
      long matchedId = sample.path("matchedRoute").path("databaseId").asLong();
      business.requireSimulationRoute(matchedId, demoMode);
      for (var segment : routes.segments(matchedId))
        if (mapSource.equals(segment.mapSource()))
          pool.putIfAbsent(TaskMatchingService.roadKey(segment), segment);
    }
    var domain =
        pool.values().stream()
            .map(
                s ->
                    new RouteSegment(
                        Long.toString(s.id()),
                        s.sequence(),
                        s.fromCity(),
                        s.toCity(),
                        s.roadName(),
                        s.roadType(),
                        s.distanceKm(),
                        s.estimatedMinutes(),
                        s.mapSource(),
                        s.mapResponseReference(),
                        status(s.truckAccessStatus()),
                        s.truckEvidenceSource(),
                        s.truckEvidenceReference(),
                        s.verifiedAt()))
            .toList();
    var built =
        GreedyRouteBuilder.build(
            "CANDIDATE",
            plan.originCity(),
            plan.destinationCity(),
            domain,
            rate,
            policy.weights(),
            demoMode);
    if (built.isEmpty())
      return save(
          planId, null, "NO_RECOMMENDATION", "已观测且同源核验的路段不能贪心构成连续同 OD 路线。", snapshot, actor);
    var selected =
        built.get().route().segments().stream()
            .map(
                s ->
                    pool.values().stream()
                        .filter(p -> Long.toString(p.id()).equals(s.id()))
                        .findFirst()
                        .orElseThrow())
            .toList();
    for (int i = 1; i < selected.size(); i++) {
      var a = RoadGeometry.wgs84(RoadGeometry.parse(selected.get(i - 1).geometryJson(), json));
      var b = RoadGeometry.wgs84(RoadGeometry.parse(selected.get(i).geometryJson(), json));
      if (RoadGeometry.distanceKm(a.getLast(), b.getFirst()) > .03)
        return save(
            planId, null, "NO_RECOMMENDATION", "贪心路段在城市节点相接，但道路端点缺少可核验连接。", snapshot, actor);
    }
    var metrics =
        List.of(
            metrics("PLAN", plan, rate, estimateSource),
            metrics(
                "CANDIDATE",
                selected,
                plan.originCity(),
                plan.destinationCity(),
                rate,
                estimateSource));
    var scores = RouteScoreCalculator.scoreSameOd(metrics, policy.weights());
    snapshot.put("metrics", metrics);
    snapshot.put("scores", scores);
    snapshot.put("normalizationBounds", scores.getFirst().bounds());
    snapshot.put("selectionReasons", built.get().selectionReasons());
    snapshot.put("candidateDemoOnly", built.get().demoOnly());
    var planKeys = plan.segments().stream().map(TaskMatchingService::roadKey).toList();
    var selectedKeys = selected.stream().map(TaskMatchingService::roadKey).toList();
    snapshot.put("roadSequenceDifferent", !planKeys.equals(selectedKeys));
    snapshot.put(
        "candidateCombinationActuallyDriven",
        samples.stream()
            .anyMatch(s -> readList(s.path("actualRoadSequence")).equals(selectedKeys)));
    if (planKeys.equals(selectedKeys))
      return save(planId, null, "NO_RECOMMENDATION", "贪心候选与计划使用相同道路序列，没有不同的改进版本。", snapshot, actor);
    String candidateSource = built.get().demoOnly() ? "DEMO_ONLY" : plan.estimateSource();
    long candidateId =
        routes.create(
            new RouteManagementService.RouteInput(
                plan.name() + " 优化候选",
                plan.originCity(),
                plan.destinationCity(),
                candidateSource,
                planId),
            actor);
    int sequence = 1;
    for (var segment : selected) {
      jdbc.update(
          """
INSERT INTO route_segments(route_id,sequence_number,from_city,to_city,road_name,road_type,distance_km,estimated_minutes,geometry_json,map_source,map_request_json,map_response_reference,truck_access_status,truck_evidence_source,truck_evidence_reference,verified_at,verified_by)
SELECT ?,?,from_city,to_city,road_name,road_type,distance_km,estimated_minutes,geometry_json,map_source,map_request_json,map_response_reference,truck_access_status,truck_evidence_source,truck_evidence_reference,verified_at,verified_by FROM route_segments WHERE id=?
""",
          candidateId,
          sequence++,
          segment.id());
    }
    jdbc.update("UPDATE routes SET route_status='CANDIDATE' WHERE id=?", candidateId);
    snapshot.put("candidate", routes.route(candidateId));
    var score = scores.get(1);
    var bounds = score.bounds();
    String status =
        built.get().demoOnly()
            ? "DEMO_ONLY"
            : score.compositeScore() > scores.getFirst().compositeScore() + 1e-9
                ? "PRELIMINARY"
                : "NO_IMPROVEMENT";
    String reason =
        "DEMO_ONLY".equals(status)
            ? "DEMO_MODE - 基于 " + samples.size() + " 次演示运输的技术验证候选；候选仅供演示，不能发布为正式路线。"
            : "PRELIMINARY".equals(status)
                ? "基于 " + samples.size() + " 次有效运输的初步推荐；候选版本需人工复核发布，未覆盖原路线。"
                : "候选综合分未优于计划路线，保留比较记录，不生成推荐。";
    if (!"DEMO_ONLY".equals(status))
      jdbc.update(
          """
INSERT INTO route_optimization_runs(origin_city,destination_city,plan_route_id,candidate_route_id,sample_count,recommendation_status,estimate_source,time_min,time_max,distance_min,distance_max,fuel_min,fuel_max,time_weight,distance_weight,fuel_weight,time_score,distance_score,fuel_score,composite_score,selection_reason)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
""",
          plan.originCity(),
          plan.destinationCity(),
          planId,
          candidateId,
          samples.size(),
          status,
          estimateSource,
          bounds.travelTime().minimum(),
          bounds.travelTime().maximum(),
          bounds.distance().minimum(),
          bounds.distance().maximum(),
          bounds.fuel().minimum(),
          bounds.fuel().maximum(),
          policy.timeWeight(),
          policy.distanceWeight(),
          policy.fuelWeight(),
          score.travelTimeScore(),
          score.distanceScore(),
          score.fuelScore(),
          score.compositeScore(),
          reason);
    return save(planId, candidateId, status, reason, snapshot, actor);
  }

  private static RouteSegment.TruckAccessStatus status(String value) {
    try {
      return RouteSegment.TruckAccessStatus.valueOf(value);
    } catch (Exception e) {
      throw new IllegalStateException("路线段货车核验状态无效。");
    }
  }

  private Evaluation save(
      long planId,
      Long candidateId,
      String status,
      String reason,
      Map<String, Object> snapshot,
      UserPrincipal actor) {
    snapshot.put("status", status);
    snapshot.put(
        "label",
        "PRELIMINARY".equals(status)
            ? reason
            : "DEMO_ONLY".equals(status) ? "演示候选（不作正式推荐）" : "暂无推荐");
    snapshot.put("reason", reason);
    var key = new GeneratedKeyHolder();
    String value = write(snapshot);
    jdbc.update(
        c -> {
          var s =
              c.prepareStatement(
                  "INSERT INTO"
                      + " optimization_evaluations(plan_route_id,candidate_route_id,recommendation_status,snapshot_json,created_by)"
                      + " VALUES(?,?,?,?,?)",
                  new String[] {"id"});
          s.setLong(1, planId);
          s.setObject(2, candidateId);
          s.setString(3, status);
          s.setString(4, value);
          s.setLong(5, actor.id());
          return s;
        },
        key);
    long id = Objects.requireNonNull(key.getKey()).longValue();
    business.audit(
        actor.id(),
        "OPTIMIZATION_EVALUATED",
        Long.toString(id),
        Map.of("status", status, "planRouteId", planId));
    return new Evaluation(id, Instant.now(), read(value));
  }

  private static RouteMetrics metrics(
      String id, RouteManagementService.RouteView route, double rate, String source) {
    return metrics(id, route.segments(), route.originCity(), route.destinationCity(), rate, source);
  }

  private static RouteMetrics metrics(
      String id,
      List<RouteManagementService.SegmentView> segments,
      String origin,
      String destination,
      double rate,
      String source) {
    double distance =
        segments.stream().mapToDouble(RouteManagementService.SegmentView::distanceKm).sum();
    return new RouteMetrics(
        id,
        origin,
        destination,
        segments.stream().mapToDouble(RouteManagementService.SegmentView::estimatedMinutes).sum(),
        distance,
        distance * rate / 100,
        source);
  }

  private static List<String> readList(JsonNode node) {
    var values = new ArrayList<String>();
    node.forEach(n -> values.add(n.asText()));
    return values;
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
