package cn.logistics.platform.service;

import cn.logistics.platform.domain.GpsPointEvent;
import cn.logistics.platform.domain.RouteDefinition;
import cn.logistics.platform.domain.RouteSegment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Generates deterministic raw GPS points and publishes them to the raw-event topic. */
@Service
public class GpsSimulationService {

    private static final Map<String, Coordinate> CITY_COORDINATES = coordinates();
    private static final Logger log = LoggerFactory.getLogger(GpsSimulationService.class);

    private final DemoDataService demoData;
    private final KafkaTemplate<String, GpsPointEvent> kafkaTemplate;
    private final String topic;
    private final boolean demoModeEnabled;

    @org.springframework.beans.factory.annotation.Autowired
    public GpsSimulationService(DemoDataService demoData,
                                KafkaTemplate<String, GpsPointEvent> kafkaTemplate,
                                @Value("${app.kafka.gps-topic:logistics.gps.raw}") String topic,
                                @Value("${app.demo-mode.enabled:false}") boolean demoModeEnabled) {
        this.demoData = demoData;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.demoModeEnabled = demoModeEnabled;
    }

    public SimulationResult start(String taskCode) {
        RouteDefinition route = demoData.simulationRoute(taskCode);
        if (!demoModeEnabled && !route.isOptimizationVerified()) {
            throw new IllegalStateException("正式模拟只允许已核验路线；请完成货车依据核验，或仅在验收环境显式开启 DEMO_MODE。");
        }
        List<GpsPointEvent> points = generate(route, taskCode);
        log.info("gps-simulation start taskCode={} routeId={} topic={} pointCount={} sourceType=SIMULATED",
                taskCode, route.id(), topic, points.size());
        int published = 0;
        try {
            for (GpsPointEvent point : points) {
                kafkaTemplate.send(topic, taskCode, point).get(10, TimeUnit.SECONDS);
                published++;
            }
        } catch (Exception exception) {
            log.error("gps-simulation failed taskCode={} topic={} published={}/{}", taskCode, topic, published,
                    points.size(), exception);
            throw new IllegalStateException("Kafka 发布 GPS 事件失败，已发布 " + published + "/" + points.size()
                    + " 条，topic=" + topic, exception);
        }
        log.info("gps-simulation completed taskCode={} topic={} published={} firstTimestamp={} lastTimestamp={}",
                taskCode, topic, published, points.getFirst().timestamp(), points.getLast().timestamp());
        String message=demoModeEnabled?"DEMO_MODE - 演示环境。固定路线插值生成；原始事件未写入城市或道路匹配字段，待核验路线不代表货车通行已核验。":"固定路线插值生成，原始事件未写入城市或道路匹配字段。";
        return new SimulationResult(taskCode, route.id(), topic, published,
                points.getFirst().timestamp(), points.getLast().timestamp(), "SIMULATED",demoModeEnabled,message);
    }

    List<GpsPointEvent> generate(RouteDefinition route, String taskCode) {
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String vehicleId = "SIM-VEHICLE-" + taskCode;
        var points = new java.util.ArrayList<GpsPointEvent>();
        int sequence = 1;
        int minuteOffset = 0;
        for (RouteSegment segment : route.segments()) {
            Coordinate from = coordinate(segment.fromCity());
            Coordinate to = coordinate(segment.toCity());
            int firstInterpolation = points.isEmpty() ? 0 : 1;
            double minutesPerPoint = Math.max(1, segment.estimatedMinutes() / 3.0);
            double speed = Math.max(20, segment.distanceKm() / Math.max(1, segment.estimatedMinutes()) * 60);
            for (int interpolation = firstInterpolation; interpolation <= 3; interpolation++) {
                double ratio = interpolation / 3.0;
                points.add(new GpsPointEvent(taskCode, vehicleId, route.id(),
                        start.plus(minuteOffset, ChronoUnit.MINUTES),
                        from.latitude() + (to.latitude() - from.latitude()) * ratio,
                        from.longitude() + (to.longitude() - from.longitude()) * ratio,
                        Math.min(110, speed), "SIMULATED", demoModeEnabled, sequence++));
                minuteOffset += Math.max(1, Math.round(minutesPerPoint));
            }
        }
        return List.copyOf(points);
    }

    private static Coordinate coordinate(String city) {
        Coordinate coordinate = CITY_COORDINATES.get(city);
        if (coordinate != null) return coordinate;
        int hash = Math.abs(city.hashCode());
        return new Coordinate(20 + hash % 1800 / 100.0, 100 + hash % 3000 / 100.0);
    }

    private static Map<String, Coordinate> coordinates() {
        Map<String, Coordinate> values = new LinkedHashMap<>();
        values.put("北京", new Coordinate(39.9042, 116.4074));
        values.put("天津", new Coordinate(39.0842, 117.2009));
        values.put("济南", new Coordinate(36.6512, 117.1201));
        values.put("徐州", new Coordinate(34.2058, 117.2841));
        values.put("南京", new Coordinate(32.0603, 118.7969));
        values.put("上海", new Coordinate(31.2304, 121.4737));
        values.put("沧州", new Coordinate(38.3045, 116.8387));
        values.put("广州", new Coordinate(23.1291, 113.2644));
        values.put("清远", new Coordinate(23.6818, 113.0560));
        values.put("韶关", new Coordinate(24.8104, 113.5972));
        values.put("郴州", new Coordinate(25.7705, 113.0149));
        values.put("长沙", new Coordinate(28.2282, 112.9388));
        values.put("武汉", new Coordinate(30.5928, 114.3055));
        values.put("成都", new Coordinate(30.5728, 104.0668));
        values.put("德阳", new Coordinate(31.1270, 104.3979));
        values.put("绵阳", new Coordinate(31.4675, 104.6791));
        values.put("广元", new Coordinate(32.4355, 105.8434));
        values.put("汉中", new Coordinate(33.0676, 107.0233));
        values.put("西安", new Coordinate(34.3416, 108.9398));
        return Map.copyOf(values);
    }

    private record Coordinate(double latitude, double longitude) {}

    public record SimulationResult(String taskCode, String routeId, String topic, int publishedCount,
                                   Instant firstTimestamp, Instant lastTimestamp, String sourceType,
                                   boolean demoMode, String message) {}
}
