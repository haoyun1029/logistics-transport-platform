package cn.logistics.platform.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class AmapTruckRouteClientTest {
    HttpServer server;
    String response;
    AtomicReference<String> query = new AtomicReference<>();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/truck", e -> {
            query.set(e.getRequestURI().getRawQuery());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        }); server.start();
        response = success("[{\"road\":\"TEST ROAD\",\"polyline\":\"116.1,39.1;116.2,39.2\"},{\"road\":\"TEST ROAD 2\",\"polyline\":\"116.2,39.2;116.3,39.3\"}]");
    }
    @AfterEach void stop() { server.stop(0); }
    String success(String steps) { return "{\"errcode\":0,\"errmsg\":\"OK\",\"data\":{\"route\":{\"paths\":[{\"distance\":12000,\"duration\":900,\"restriction\":-1,\"steps\":" + steps + "}]}}}"; }
    AmapTruckRouteClient client(String key) { return new AmapTruckRouteClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort() + "/truck", key); }
    AmapTruckRouteClient.TruckRouteRequest request() { return new AmapTruckRouteClient.TruckRouteRequest("116.1,39.1", "116.3,39.3", "TEST A", "TEST B", 10, "3", 10, "京A12345", 3.0, 2.4, 7.0, 2); }
    @Test void reportsMissingKeyBeforeNetwork() {
        assertTrue(assertThrows(IllegalStateException.class, () -> client("").quote(request())).getMessage().contains("未配置"));
        assertNull(query.get());
    }
    @Test void usesOfficialV4TruckParametersAndLeavesApprovalPending() {
        var q = client("test-key").quote(request());
        Map<String,String> params = Arrays.stream(query.get().split("&")).map(s -> s.split("=",2)).collect(Collectors.toMap(p -> p[0], p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
        assertEquals("京", params.get("province")); assertEquals("A12345", params.get("number")); assertFalse(params.containsKey("plate"));
        assertEquals("10", params.get("strategy")); assertEquals("10.000", params.get("load")); assertEquals("7.000", params.get("weight"));
        assertEquals("3.000",params.get("height")); assertEquals("2",params.get("axis")); assertEquals("1",params.get("showpolyline")); assertEquals("0",params.get("nosteps"));
        assertFalse(q.requestParameters().containsKey("key")); assertFalse(q.rawResponse().contains("test-key"));
        assertEquals(12,q.distanceKm()); assertEquals(15,q.durationMinutes()); assertEquals(-1,q.restriction());
        assertEquals("GCJ02",q.geometry().coordinateSystem()); assertEquals(3,q.geometry().coordinates().size()); assertEquals(2,q.roadNames().size());
        assertEquals("PENDING_TRUCK_EVIDENCE",q.verificationStatus());
    }
    @Test void ordinaryRoadQueryNeverSendsTruckProfileOrClaimsTruckApproval() {
        // Use the ordinary v3 envelope rather than the truck v4 envelope.
        response = "{\"status\":\"1\",\"info\":\"OK\",\"route\":{\"paths\":[{\"distance\":12000,\"duration\":900,\"restriction\":-1,\"steps\":[{\"road\":\"TEST ROAD\",\"polyline\":\"116.1,39.1;116.2,39.2\"}]}]}}";
        var client = new AmapTruckRouteClient(RestClient.builder(), "https://example.invalid/paid-truck", "test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/truck");
        var q = client.quoteRoadGeometry(request());
        assertEquals("AMAP_DRIVING_ROAD_GEOMETRY_V3", q.dataSource()); assertEquals("PENDING_PUBLIC_TRUCK_EVIDENCE", q.verificationStatus());
        assertEquals(0,q.restriction(), "Ordinary driving restriction is not a truck assessment");
        for (String name : new String[]{"size", "load", "weight", "province", "number"}) assertFalse(q.requestParameters().containsKey(name));
        assertNotNull(q.geometry()); assertEquals("0",q.requestParameters().get("strategy"));
    }
    @Test void retainsPermissionErrorAndSanitizesUpstreamEcho() {
        response = "{\"errcode\":10012,\"errmsg\":\"INSUFFICIENT_PRIVILEGES\",\"errdetail\":\"test-key\"}";
        var e = assertThrows(AmapTruckRouteClient.MapServiceException.class, () -> client("test-key").quote(request()));
        assertEquals("10012",e.code()); assertTrue(e.getMessage().contains("INSUFFICIENT_PRIVILEGES"));
        assertFalse(e.getMessage().contains("test-key")); assertFalse(e.responseExcerpt().contains("test-key"));
    }
    @Test void missingOrDisjointStepNeverInventsConnector() {
        for (String steps : new String[]{"[]", "[{\"polyline\":\"116.1,39.1;116.2,39.2\"},{}]", "[{\"polyline\":\"116.1,39.1;116.2,39.2\"},{\"polyline\":\"117,40;118,41\"}]", "[{\"polyline\":\"116.1,39.1;NaN,39\"}]"}) {
            response=success(steps); var q=client("test-key").quote(request()); assertNull(q.geometry()); assertEquals("PENDING_TRUCK_EVIDENCE",q.verificationStatus());
        }
    }
    @Test void rejectsInvalidCoordinatesTruckSizeStrategyAndMassWithoutNetwork() {
        for (var r : new AmapTruckRouteClient.TruckRouteRequest[]{
            new AmapTruckRouteClient.TruckRouteRequest("181,39", "116,40", "A", "B", 10,"2",10,null),
            new AmapTruckRouteClient.TruckRouteRequest("116.1234567,39", "116,40", "A", "B",10,"2",10,null),
            new AmapTruckRouteClient.TruckRouteRequest("116,39", "116,40", "A", "B",0,"2",10,null),
            new AmapTruckRouteClient.TruckRouteRequest("116,39", "116,40", "A", "B",10,"轻型",10,null),
            new AmapTruckRouteClient.TruckRouteRequest("116,39", "116,40", "A", "B",10,"2",Double.NaN,null)})
            assertThrows(IllegalArgumentException.class, () -> client("test-key").quote(r));
        assertNull(query.get());
    }
    @Test void rejectsMalformedResponseMetrics() {
        response=success("[]").replace("12000", "0"); assertThrows(IllegalStateException.class, () -> client("test-key").quote(request()));
        response="{\"errcode\":0,\"data\":{}}"; assertThrows(IllegalStateException.class, () -> client("test-key").quote(request()));
    }
    @Test void exposesSourceStepsAndMeasuredSeamsWithoutInventingGeometry() {
        response = success("[{\"road\":\"A\",\"polyline\":\"116.1,39.1;116.2,39.2\"},{\"road\":\"B\",\"instruction\":\"turn\",\"polyline\":\"116.2001,39.2;116.3,39.3\"}]");
        var q = client("test-key").quote(request());
        assertNull(q.geometry()); assertEquals(2, q.steps().size());
        assertEquals("turn", q.steps().get(1).instruction());
        assertEquals(List.of(116.2001, 39.2), q.steps().get(1).coordinates().getFirst());
        var issue = q.geometryIssues().getFirst();
        assertEquals("DISCONNECTED", issue.type()); assertEquals(1, issue.previousStepNumber()); assertEquals(2, issue.stepNumber());
        assertTrue(issue.distanceMeters() > 8 && issue.distanceMeters() < 9);
        assertEquals(q.steps().getFirst().coordinates().getLast(), issue.from());
        assertEquals(q.steps().getLast().coordinates().getFirst(), issue.to());
    }
    @Test void invalidStepCannotLeakPartialPolylineOrBridgeMissingSection() {
        response = success("[{\"polyline\":\"116.1,39.1;116.2,39.2\"},{\"polyline\":\"116.2,39.2;bad;116.3,39.3\"},{\"polyline\":\"116.4,39.4;116.5,39.5\"}]");
        var q = client("test-key").quote(request());
        assertNull(q.geometry()); assertTrue(q.steps().get(1).coordinates().isEmpty());
        assertEquals(1, q.geometryIssues().size()); assertEquals("INVALID_GEOMETRY", q.geometryIssues().getFirst().type());
        assertNull(q.geometryIssues().getFirst().from());
    }
    @Test void savedMaterialsReparseWithoutKeyOrNetworkAndKeepOriginalTime() {
        var time = java.time.Instant.parse("2026-10-24T10:00:00Z");
        var q = client("").restoreQuote("A", "B", "{\"origin\":\"116.1,39.1\",\"destination\":\"116.3,39.3\"}",
                response, time, "AMAP_TRUCK_ROUTE_API_V4");
        assertNull(query.get()); assertEquals(time, q.requestedAt()); assertEquals(2, q.steps().size());
        assertNotNull(q.geometry()); assertTrue(q.geometryIssues().isEmpty()); assertEquals("PENDING_TRUCK_EVIDENCE", q.verificationStatus());
        assertThrows(IllegalArgumentException.class, () -> client("").restoreQuote("A","B","{}",response,time,"UNKNOWN"));
    }
}
