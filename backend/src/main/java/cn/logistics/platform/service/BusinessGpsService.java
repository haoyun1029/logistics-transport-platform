package cn.logistics.platform.service;

import cn.logistics.platform.domain.RoadGeometry;
import cn.logistics.platform.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessGpsService {
  private final TransportBusinessService business;
  private final RouteManagementService routes;
  private final MongoTelemetryService telemetry;
  private final SystemPolicyService policies;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final KafkaTemplate<String, Object> kafka;
  private final String topic;

  public BusinessGpsService(
      TransportBusinessService business,
      RouteManagementService routes,
      MongoTelemetryService telemetry,
      SystemPolicyService policies,
      JdbcTemplate jdbc,
      ObjectMapper json,
      KafkaTemplate<String, Object> kafka,
      @Value("${app.kafka.gps-topic}") String topic) {
    this.business = business;
    this.routes = routes;
    this.telemetry = telemetry;
    this.policies = policies;
    this.jdbc = jdbc;
    this.json = json;
    this.kafka = kafka;
    this.topic = topic;
  }

  @Transactional
  public Result start(long taskId, Long actualRouteId, UserPrincipal actor) {
    if (actor.role() != UserRole.DRIVER) throw new ForbiddenException("只有本人任务司机可以启动模拟。");
    jdbc.queryForList("SELECT id FROM transport_tasks WHERE id=? FOR UPDATE", Long.class, taskId);
    var task = business.task(taskId, actor);
    if (!"IN_TRANSIT".equals(task.status())) throw new IllegalStateException("接单后才能模拟 GPS。");
    business.requireSimulationRoute(task.routeId(), task.demoMode());
    var existingSimulation = telemetry.simulationCheck(task.code());
    if (!"CONNECTED".equals(existingSimulation.status()))
      throw new IllegalStateException("MongoDB 轨迹暂不可用，无法确认是否已有完整轨迹；为避免重复发布，本次未启动模拟 GPS。请恢复 MongoDB/SSH 隧道后重试。");
    if (existingSimulation.hasCompleteRun()) {
      business.audit(actor.id(), "ROAD_GPS_SIMULATION_DUPLICATE_SKIPPED", task.code(), Map.of(
          "runId", existingSimulation.completeRunId(),
          "pointCount", existingSimulation.completePointCount()));
      return new Result(existingSimulation.completeRunId(), 0, topic, "SIMULATED", task.demoMode(), true,
          "已检测到完整模拟轨迹（" + existingSimulation.completePointCount() + " 点），本次跳过重复发布。");
    }
    long actualId = actualRouteId == null ? simulationScenarioRoute(task) : actualRouteId;
    business.requireSimulationRoute(actualId, task.demoMode());
    var plan = routes.route(task.routeId());
    var actual = routes.route(actualId);
    if (!plan.originCity().equals(actual.originCity())
        || !plan.destinationCity().equals(actual.destinationCity()))
      throw new IllegalArgumentException("模拟替代走法必须与计划为同一 OD。");
    List<RoadGeometry.Point> planned = path(plan, json);
    List<RoadGeometry.Point> driven = path(actual, json);
    if (planned.size() > 20000) throw new IllegalArgumentException("计划几何超过单事件上下文预算，请先拆分任务路线。");
    var points = RoadGeometry.sample(driven, 1.5);
    if (points.size() > 1500) throw new IllegalArgumentException("模拟轨迹超过 1500 点，请缩短任务路线。");
    var policy = policies.current();
    String runId = UUID.randomUUID().toString();
    Instant start = Instant.now();
    double minutes =
        actual.segments().stream()
            .mapToDouble(RouteManagementService.SegmentView::estimatedMinutes)
            .sum();
    var context = new LinkedHashMap<String, Object>();
    context.put(
        "geometrySource", task.demoMode() ? "DEMO_MODE_ROUTE_GEOMETRY" : "SERVER_VERIFIED_ROUTE");
    context.put("demoMode", task.demoMode());
    context.put("routeVerificationStatus", plan.verificationStatus());
    context.put("routeVersionId", task.routeId());
    context.put("coordinateSystem", "WGS84");
    context.put(
        "coordinates", planned.stream().map(p -> List.of(p.longitude(), p.latitude())).toList());
    context.put("policy", policy);
    int published = 0;
    double traversed = 0;
    try {
      double total = RoadGeometry.lengthKm(driven);
      double speed = total / minutes * 60;
      for (int i = 0; i < points.size(); i++) {
        if (i > 0) traversed += RoadGeometry.distanceKm(points.get(i - 1), points.get(i));
        var event = new LinkedHashMap<String, Object>();
        event.put("taskId", task.code());
        event.put("vehicleId", task.vehicleId().toString());
        event.put("routeId", "DB-" + task.routeId());
        event.put(
            "timestamp", start.plusMillis((long) (traversed / total * minutes * 60000)).toString());
        event.put("latitude", points.get(i).latitude());
        event.put("longitude", points.get(i).longitude());
        event.put("speedKmh", speed);
        event.put("sourceType", "SIMULATED");
        event.put("demoMode", task.demoMode());
        event.put("coordinateSystem", "WGS84");
        event.put("sequence", i + 1);
        event.put("simulationRunId", runId);
        event.put("totalPointCount", points.size());
        event.put("plannedRoadContext", context);
        kafka.send(topic, task.code(), event).get(10, TimeUnit.SECONDS);
        published++;
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "模拟发布失败，已发送 " + published + "/" + points.size() + " 点。部分运行不能作为有效路线样本。");
    }
    business.audit(
        actor.id(),
        "ROAD_GPS_SIMULATION",
        task.code(),
        Map.of(
            "runId",
            runId,
            "planRouteId",
            task.routeId(),
            "actualRouteId",
            actualId,
            "scenarioType",
            task.demoMode() && actualId != task.routeId()
                ? "DEMO_PRECONFIGURED_SAME_OD_ALTERNATIVE"
                : "ASSIGNED_PLAN_ROUTE",
            "pointCount",
            points.size(),
            "policyVersion",
            policy.version(),
            "demoMode",
            task.demoMode()));
    String note =
        task.demoMode()
            ? "DEMO_MODE - 演示环境。轨迹由服务端预设的同 OD 模拟路线几何生成；路线匹配只读取 GPS 点，待核验路线不代表货车通行已核验，也不进入正式推荐。"
            : "沿已核验道路折线加速发布；GPS 时间与系统实际运输耗时分开展示。";
    return new Result(runId, published, topic, "SIMULATED", task.demoMode(), false, note);
  }

  /**
   * Demo route choice is a server-side fixture. The driver only accepts the task;
   * downstream matching must infer the driven route from the emitted GPS points.
   */
  private long simulationScenarioRoute(TransportBusinessService.TaskView task) {
    if (!task.demoMode()) return task.routeId();
    return routes.routes().stream()
        .filter(route -> route.databaseId() != task.routeId())
        .filter(route -> task.originCity().equals(route.originCity())
            && task.destinationCity().equals(route.destinationCity()))
        .filter(route -> !route.segments().isEmpty()
            && route.segments().stream().allMatch(segment -> segment.geometryJson() != null))
        .filter(route -> "VERIFIED".equals(route.verificationStatus())
            || "PENDING_VERIFICATION".equals(route.verificationStatus()))
        .filter(route -> {
          try {
            business.requireSimulationRoute(route.databaseId(), true);
            return true;
          } catch (IllegalArgumentException | IllegalStateException ignored) {
            return false;
          }
        })
        .sorted(Comparator
            .comparing((RouteManagementService.RouteView route) ->
                "CANDIDATE".equals(route.verificationStatus()) ? 0 : 1)
            .thenComparingLong(RouteManagementService.RouteView::databaseId))
        .mapToLong(RouteManagementService.RouteView::databaseId)
        .findFirst()
        .orElse(task.routeId());
  }

  public static List<RoadGeometry.Point> path(
      RouteManagementService.RouteView route, ObjectMapper json) {
    var points = new ArrayList<RoadGeometry.Point>();
    for (var s : route.segments())
      points.addAll(RoadGeometry.wgs84(RoadGeometry.parse(s.geometryJson(), json)));
    return List.copyOf(points);
  }

  public record Result(
      String runId,
      int publishedCount,
      String topic,
      String sourceType,
      boolean demoMode,
      boolean reusedExistingRun,
      String note) {}
}
