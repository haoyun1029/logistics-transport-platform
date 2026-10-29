package cn.logistics.platform.service;

import cn.logistics.platform.domain.RouteDefinition;
import cn.logistics.platform.domain.RouteSegment;
import cn.logistics.platform.domain.TripSample;
import cn.logistics.platform.optimization.RouteComparison;
import cn.logistics.platform.optimization.RouteDifference;
import cn.logistics.platform.optimization.RouteScore;
import cn.logistics.platform.optimization.ScoringWeights;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reproducible in-memory data for the local demonstration. Production writes will
 * move behind MySQL/MongoDB repositories without changing the response contract.
 */
@Service
public class DemoDataService {

    private static final String ESTIMATE_SOURCE = "NOT_AVAILABLE_UNVERIFIED_ROUTE_FIXTURE";
    private static final Instant SAMPLE_START = Instant.parse("2026-10-18T01:00:00Z");
    private static final Instant SAMPLE_END = Instant.parse("2026-10-18T19:10:00Z");

    private final Map<String, RouteDefinition> routes;
    private final Map<String, DemoTask> tasks;
    private final ScoringWeights weights = ScoringWeights.defaults();

    public DemoDataService() {
        RouteDefinition beijingShanghai = route("PLAN-BJ-SH", "北京", "上海", List.of(
                segment("bj-tj", 1, "北京", "天津", "京津高速 G2", "高速", 135, 105),
                segment("tj-jinan", 2, "天津", "济南", "京沪高速 G2", "高速", 320, 220),
                segment("jinan-xuzhou", 3, "济南", "徐州", "京台高速 G3", "高速", 300, 215),
                segment("xuzhou-nanjing", 4, "徐州", "南京", "京沪高速 G2", "高速", 280, 195),
                segment("nanjing-shanghai", 5, "南京", "上海", "沪蓉高速 G42", "高速", 300, 210)));
        RouteDefinition beijingShanghaiCandidate = route("CANDIDATE-BJ-SH-01", "北京", "上海", List.of(
                segment("bj-tj-c", 1, "北京", "天津", "京津高速 G2", "高速", 135, 105),
                segment("tj-cangzhou", 2, "天津", "沧州", "京沪高速 G2", "高速", 150, 110),
                segment("cangzhou-jinan", 3, "沧州", "济南", "京沪高速 G2", "高速", 220, 155),
                segment("jinan-nanjing", 4, "济南", "南京", "京台高速 G3/京沪高速 G2", "高速", 575, 390),
                segment("nanjing-shanghai-c", 5, "南京", "上海", "沪蓉高速 G42", "高速", 300, 210)));
        RouteDefinition guangzhouWuhan = route("PLAN-GZ-WH", "广州", "武汉", List.of(
                segment("gz-qingyuan", 1, "广州", "清远", "许广高速 G0421", "高速", 85, 70),
                segment("qingyuan-shaoguan", 2, "清远", "韶关", "京港澳高速 G4", "高速", 150, 110),
                segment("shaoguan-chengzhou", 3, "韶关", "郴州", "京港澳高速 G4", "高速", 180, 130),
                segment("chengzhou-changsha", 4, "郴州", "长沙", "京港澳高速 G4", "高速", 320, 225),
                segment("changsha-wuhan", 5, "长沙", "武汉", "京港澳高速 G4", "高速", 360, 250)));
        RouteDefinition chengduXian = route("PLAN-CD-XA", "成都", "西安", List.of(
                segment("cd-deyang", 1, "成都", "德阳", "成绵高速 G5", "高速", 70, 55),
                segment("deyang-mianyang", 2, "德阳", "绵阳", "成绵高速 G5", "高速", 55, 45),
                segment("mianyang-guangyuan", 3, "绵阳", "广元", "京昆高速 G5", "高速", 245, 180),
                segment("guangyuan-hanzhong", 4, "广元", "汉中", "京昆高速 G5", "高速", 245, 185),
                segment("hanzhong-xian", 5, "汉中", "西安", "京昆高速 G5", "高速", 250, 190)));

        this.routes = new LinkedHashMap<>();
        this.routes.put(beijingShanghai.id(), beijingShanghai);
        this.routes.put(beijingShanghaiCandidate.id(), beijingShanghaiCandidate);
        this.routes.put(guangzhouWuhan.id(), guangzhouWuhan);
        this.routes.put(chengduXian.id(), chengduXian);

        this.tasks = new LinkedHashMap<>();
        this.tasks.put("DEMO-2026-001", new DemoTask(
                "DEMO-2026-001", "北京", "上海", beijingShanghai.id(), "COMPLETED",
                new TripSample("DEMO-2026-001", "北京", "上海", true, SAMPLE_START, SAMPLE_END,
                        1194, 405, true, true),
                beijingShanghaiCandidate.id()));
        this.tasks.put("DEMO-2026-002", new DemoTask(
                "DEMO-2026-002", "广州", "武汉", guangzhouWuhan.id(), "PENDING_ACCEPTANCE",
                null, null));
    }

    public synchronized Overview overview() {
        List<RouteView> routeViews = routes.values().stream().map(this::routeView).toList();
        List<TaskView> taskViews = tasks.values().stream().map(this::taskView).toList();
        List<ScoreView> scores = List.of();
        RecommendationSummary recommendation = recommendation(taskViews);
        return new Overview(weights, routeViews, taskViews, scores, recommendation,
                new ServiceStatus("Kafka", "192.168.146.128:9092", "CONNECTED"),
                new ServiceStatus("Flink", "192.168.146.128:8081", "CONNECTED"),
                new ServiceStatus("MySQL", "192.168.146.128:3306", "CONFIGURED"),
                new ServiceStatus("MongoDB", "VM 127.0.0.1:27017", "RUNNING_VM_LOCAL"),
                new ServiceStatus("HDFS", "hdfs://hadoop101:8020", "RUNNING"));
    }

    private RecommendationSummary recommendation(List<TaskView> taskViews) {
        int validSamples = (int) taskViews.stream().filter(task -> "VALID".equals(task.sampleStatus())).count();
        RouteDefinition plan = routes.get("PLAN-BJ-SH");
        RouteDefinition candidate = routes.get("CANDIDATE-BJ-SH-01");
        if (!plan.isOptimizationVerified() || !candidate.isOptimizationVerified()) {
            return new RecommendationSummary("NO_RECOMMENDATION", "暂无推荐", "NONE", validSamples,
                    "计划路线或候选路线仍有路段待货车依据核验，当前记录已排除正式优化。",
                    plan.id(), candidate.id());
        }
        if (validSamples == 0) {
            return new RecommendationSummary("NO_RECOMMENDATION", "暂无推荐", "NONE", 0,
                    "缺少有效运输样本，不能生成初步推荐。", plan.id(), candidate.id());
        }
        return new RecommendationSummary("PRELIMINARY", "基于 " + validSamples + " 次有效运输的初步推荐",
                "PRELIMINARY_ONE_SAMPLE", validSamples,
                "候选路线已通过硬约束并在同一 OD 内完成综合评分；该结果不宣称全局最优。",
                plan.id(), candidate.id());
    }

    public synchronized List<RouteView> routes() {
        return routes.values().stream().map(this::routeView).toList();
    }

    public synchronized List<TaskView> tasks() {
        return tasks.values().stream().map(this::taskView).toList();
    }

    public synchronized RouteDefinition simulationRoute(String taskCode) {
        DemoTask task = requireTask(taskCode);
        if ("PENDING_ACCEPTANCE".equals(task.status())) {
            throw new IllegalStateException("任务必须先接单后才能启动 GPS 模拟。当前状态: " + task.status());
        }
        return routes.get(task.routeId());
    }

    public synchronized TaskView accept(String taskCode) {
        DemoTask task = requireTask(taskCode);
        if (!"PENDING_ACCEPTANCE".equals(task.status())) {
            throw new IllegalStateException("只有待接单任务可以接受。当前状态: " + task.status());
        }
        task.setStatus("IN_TRANSIT");
        task.setStartedAt(Instant.now());
        return taskView(task);
    }

    public synchronized TaskView complete(String taskCode, CompletionRequest request) {
        DemoTask task = requireTask(taskCode);
        if (!"IN_TRANSIT".equals(task.status())) {
            throw new IllegalStateException("只有运输中任务可以确认送达。当前状态: " + task.status());
        }
        if (request == null || request.actualDistanceKm() <= 0 || request.actualFuelLiters() <= 0) {
            throw new IllegalArgumentException("实际里程和实际油耗必须为正数。");
        }
        Instant completedAt = Instant.now();
        // Keep the demo deterministic on filesystems/clocks with millisecond resolution.
        if (!completedAt.isAfter(task.startedAt())) {
            completedAt = task.startedAt().plusMillis(1);
        }
        task.setStatus("COMPLETED");
        task.setSample(new TripSample(task.code(), task.originCity(), task.destinationCity(), true,
                task.startedAt(), completedAt, request.actualDistanceKm(), request.actualFuelLiters(),
                request.routeDifferenceRecoverable(), true));
        task.setCompletedAt(completedAt);
        return taskView(task);
    }

    public synchronized TaskView cancel(String taskCode) {
        DemoTask task = requireTask(taskCode);
        if ("COMPLETED".equals(task.status()) || "CANCELLED".equals(task.status())) {
            throw new IllegalStateException("已完成或已取消任务不能再次取消。");
        }
        task.setStatus("CANCELLED");
        return taskView(task);
    }

    private DemoTask requireTask(String taskCode) {
        DemoTask task = tasks.get(taskCode);
        if (task == null) throw new IllegalArgumentException("演示任务不存在。");
        return task;
    }

    private RouteView routeView(RouteDefinition route) {
        return new RouteView(route.id(), route.version(), route.originCity(), route.destinationCity(),
                route.citySequence(), route.segments().stream().map(this::segmentView).toList(),
                route.isOptimizationVerified() ? "VERIFIED" : "PENDING_VERIFICATION",
                ESTIMATE_SOURCE, null);
    }

    private SegmentView segmentView(RouteSegment segment) {
        return new SegmentView(segment.sequence(), segment.fromCity(), segment.toCity(), segment.roadName(),
                segment.roadType(), segment.distanceKm(), segment.estimatedMinutes(), segment.mapSource(),
                segment.mapRequestReference(), segment.truckAccessStatus().name(), segment.truckEvidenceSource(),
                segment.truckEvidenceReference(), segment.verifiedAt());
    }

    private TaskView taskView(DemoTask task) {
        RouteDefinition planned = routes.get(task.routeId());
        RouteDifference difference = task.sample() == null ? null
                : RouteComparison.compareCitySequences(planned.citySequence(),
                routes.get(task.candidateRouteId() == null ? task.routeId() : task.candidateRouteId()).citySequence());
        String sampleStatus = task.sample() == null ? "PENDING_MATCH"
                : task.sample().isValidForOptimization() ? "VALID" : "INVALID";
        return new TaskView(task.code(), task.originCity(), task.destinationCity(), task.routeId(), task.status(),
                task.startedAt(), task.completedAt(), sampleStatus,
                task.sample() == null ? null : task.sample().actualDistanceKm(),
                task.sample() == null ? null : task.sample().actualFuelLiters(),
                task.sample() == null ? null : elapsedMinutes(task.sample()),
                task.sample() == null ? null : task.sample().exclusionReason(), difference,
                task.sample() != null && task.sample().matchedTrajectoryIsSimulated());
    }

    private static long elapsedMinutes(TripSample sample) {
        return Duration.between(sample.startedAt(), sample.completedAt()).toMinutes();
    }

    private static RouteDefinition route(String id, String origin, String destination, List<RouteSegment> segments) {
        return new RouteDefinition(id, 1, origin, destination, segments);
    }

    private static RouteSegment segment(String id, int sequence, String from, String to, String road, String type,
                                        double distance, double minutes) {
        return new RouteSegment(id, sequence, from, to, road, type, distance, minutes,
                "DEMO_FIXTURE_NOT_MAP_VERIFIED", "fixture-request-not-submitted-" + id,
                RouteSegment.TruckAccessStatus.PENDING, null,
                null, null);
    }

    public record Overview(ScoringWeights weights, List<RouteView> routes, List<TaskView> tasks,
                           List<ScoreView> scores, RecommendationSummary recommendation,
                           ServiceStatus kafka, ServiceStatus flink,
                           ServiceStatus mysql, ServiceStatus mongodb, ServiceStatus hdfs) {}

    public record RecommendationSummary(String status, String label, String evidenceLevel,
                                        int validSampleCount, String reason,
                                        String planRouteId, String candidateRouteId) {}

    public record ServiceStatus(String name, String endpoint, String status) {}

    public record RouteView(String id, int version, String originCity, String destinationCity,
                            List<String> citySequence, List<SegmentView> segments, String verificationStatus,
                            String estimateSource, Instant verifiedAt) {}

    public record SegmentView(int sequence, String fromCity, String toCity, String roadName, String roadType,
                              double distanceKm, double estimatedMinutes, String mapSource, String mapRequestReference,
                              String truckAccessStatus, String truckEvidenceSource, String truckEvidenceReference,
                              Instant verifiedAt) {}

    public record ScoreView(String routeId, double travelTimeScore, double distanceScore, double fuelScore,
                            double compositeScore, RouteScore.NormalizationBounds bounds,
                            ScoringWeights weights, String estimateSource) {}

    public record TaskView(String code, String originCity, String destinationCity, String routeId, String status,
                           Instant startedAt, Instant completedAt, String sampleStatus, Double actualDistanceKm,
                           Double actualFuelLiters, Long actualElapsedMinutes, String exclusionReason,
                           RouteDifference routeDifference, boolean simulatedTrajectory) {}

    public record CompletionRequest(double actualDistanceKm, double actualFuelLiters,
                                    boolean routeDifferenceRecoverable) {}

    private static final class DemoTask {
        private final String code;
        private final String originCity;
        private final String destinationCity;
        private final String routeId;
        private final String candidateRouteId;
        private String status;
        private Instant startedAt;
        private Instant completedAt;
        private TripSample sample;

        private DemoTask(String code, String originCity, String destinationCity, String routeId, String status,
                         TripSample sample, String candidateRouteId) {
            this.code = code;
            this.originCity = originCity;
            this.destinationCity = destinationCity;
            this.routeId = routeId;
            this.status = status;
            this.sample = sample;
            this.candidateRouteId = candidateRouteId;
            if (sample != null) {
                this.startedAt = sample.startedAt();
                this.completedAt = sample.completedAt();
            }
        }

        private String code() { return code; }
        private String originCity() { return originCity; }
        private String destinationCity() { return destinationCity; }
        private String routeId() { return routeId; }
        private String candidateRouteId() { return candidateRouteId; }
        private String status() { return status; }
        private void setStatus(String status) { this.status = status; }
        private Instant startedAt() { return startedAt; }
        private void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
        private Instant completedAt() { return completedAt; }
        private void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
        private TripSample sample() { return sample; }
        private void setSample(TripSample sample) { this.sample = sample; }
    }
}
