package cn.logistics.platform.optimization;

import cn.logistics.platform.domain.RoadGeometry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the actual additive demo seed.  This deliberately
 * reads the geometry embedded in the SQL so a fixture can pass unit tests and
 * still fail when it is imported into the demo database.
 */
class DemoSeedRouteGeometryTest {
    private static final Pattern GEOMETRY = Pattern.compile(
            "CAST\\('(?<json>\\{\\\"coordinateSystem\\\":\\\"WGS84\\\".*?\\})' AS JSON\\)",
            Pattern.DOTALL);

    @Test
    void sampledPlanAndAlternativeAreUniquelyMatched() throws Exception {
        String sql = Files.readString(seedPath());
        var matcher = GEOMETRY.matcher(sql);
        var geometries = new ArrayList<RoadGeometry.Geometry>();
        ObjectMapper objectMapper = new ObjectMapper();
        while (matcher.find()) {
            geometries.add(RoadGeometry.parse(matcher.group("json"), objectMapper));
        }
        assertEquals(4, geometries.size(), "计划/替代路线都必须包含两个相邻城市路段");
        assertEquals(2, count(sql, "route_name='新增演示计划路线：湖州—安吉—杭州 OSRM'"));
        assertEquals(2, count(sql, "route_name='新增演示替代路线：湖州—德清—杭州 OSRM'"));

        var plan = path(1001, "PLAN", geometries.subList(0, 2));
        var alternative = path(1002, "ALTERNATIVE", geometries.subList(2, 4));
        var candidates = List.of(plan, alternative);

        assertUnique(sample(plan), candidates, 1001);
        assertUnique(sample(alternative), candidates, 1002);
    }

    private static void assertUnique(List<RoadMatcher.Observation> observations,
                                     List<RoadMatcher.Path> candidates,
                                     long expectedRouteId) {
        var result = RoadMatcher.match(observations, observations.size(), candidates, .15);
        assertTrue(result.valid(), result.reason());
        assertEquals(expectedRouteId, result.matchedRouteId());
        assertTrue(result.matchedFraction() >= .9);

        var other = candidates.stream()
                .filter(path -> path.routeId() != expectedRouteId)
                .toList();
        var cross = RoadMatcher.match(observations, observations.size(), other, .15);
        assertFalse(cross.valid(), "分叉路线不能单独覆盖另一条路线");
    }

    private static RoadMatcher.Path path(long id, String signature,
                                         List<RoadGeometry.Geometry> geometries) {
        var segments = new ArrayList<RoadMatcher.Segment>();
        for (int i = 0; i < geometries.size(); i++) {
            segments.add(new RoadMatcher.Segment(i + 1, geometries.get(i).points()));
        }
        return new RoadMatcher.Path(id, signature, segments);
    }

    private static List<RoadMatcher.Observation> sample(RoadMatcher.Path path) {
        var points = new ArrayList<RoadGeometry.Point>();
        for (var segment : path.segments()) {
            if (!points.isEmpty()) points.removeLast();
            points.addAll(segment.points());
        }
        var sampled = RoadGeometry.sample(points, 1.5);
        var observations = new ArrayList<RoadMatcher.Observation>();
        for (int i = 0; i < sampled.size(); i++) {
            observations.add(new RoadMatcher.Observation(i + 1, sampled.get(i)));
        }
        return observations;
    }

    private static Path seedPath() {
        Path fromBackend = Path.of("..", "scripts", "vm", "seed-demo-route-material-v2.sql");
        if (Files.exists(fromBackend)) return fromBackend;
        return Path.of("scripts", "vm", "seed-demo-route-material-v2.sql");
    }

    private static long count(String text, String needle) {
        return text.lines().filter(line -> line.contains(needle)).count();
    }
}
