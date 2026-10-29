package cn.logistics.platform.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class OsrmRoadRouteClientTest {
    HttpServer server;
    String response;
    AtomicReference<String> uri = new AtomicReference<>(), agent = new AtomicReference<>();
    OsrmRoadRouteClient client;
    String path(int distance) { return "{\"distance\":" + distance + ",\"duration\":600,\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[120,30],[120.1,30.1],[120.2,30.2]]},\"legs\":[{\"steps\":[{\"name\":\"TEST ROAD\",\"maneuver\":{\"type\":\"depart\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[120,30],[120.1,30.1],[120.2,30.2]]}},{\"maneuver\":{\"type\":\"arrive\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[120.2,30.2]]}}]}]}"; }
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/route", e -> { uri.set(e.getRequestURI().toString()); agent.set(e.getRequestHeaders().getFirst("User-Agent"));
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type","application/json");
            e.sendResponseHeaders(200,bytes.length); e.getResponseBody().write(bytes); e.close(); }); server.start();
        response="{\"code\":\"Ok\",\"routes\":["+path(12000)+","+path(15000)+"]}";
        client = new OsrmRoadRouteClient(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort()+"/route");
    }
    @AfterEach void stop() { server.stop(0); }
    OsrmRoadRouteClient.RoadRequest request(int index) { return new OsrmRoadRouteClient.RoadRequest("120,30","120.2,30.2","A","B",List.of("120.1,30.1"),index); }
    @Test void queriesFullGeometryWithoutKeyAndPreservesWgs84AndCarProfilePendingStatus() {
        var q=client.quote(request(0));
        assertTrue(uri.get().contains("120,30;120.1,30.1;120.2,30.2")); assertTrue(uri.get().contains("overview=full"));
        assertTrue(uri.get().contains("geometries=geojson")); assertFalse(uri.get().contains("key"));
        assertTrue(agent.get().startsWith("LogisticsGraduationDemo/")); assertEquals(12,q.distanceKm());
        assertEquals("WGS84",q.geometry().coordinateSystem()); assertEquals(3,q.geometry().coordinates().size());
        assertEquals(1,q.steps().size()); assertEquals("OSRM_OSM_DRIVING_V5",q.dataSource());
        assertEquals("PENDING_PUBLIC_TRUCK_EVIDENCE",q.verificationStatus()); assertEquals(0,q.restriction());
    }
    @Test void selectedAlternativeAndOriginalRequestAreReproducedWithoutNetwork() {
        var q=client.quote(request(1)); assertEquals(15,q.distanceKm()); uri.set(null);
        var time=Instant.parse("2026-10-24T12:00:00Z");
        var saved=client.restoreQuote("A","B","{\"origin\":\"120,30\",\"destination\":\"120.2,30.2\",\"via\":\"120.1,30.1\",\"routeIndex\":\"1\"}",q.rawResponse(),time);
        assertEquals(15,saved.distanceKm()); assertEquals(time,saved.requestedAt()); assertNull(uri.get());
        assertEquals(q.geometry(),saved.geometry());
    }
    @Test void immediateRepeatIsThrottledAndDoesNotPerformAnotherRequest() {
        client.quote(request(0)); uri.set(null);
        assertThrows(IllegalStateException.class,()->client.quote(request(0))); assertNull(uri.get());
    }
    @Test void invalidCoordinatesAndExcessiveViaPointsFailBeforeNetwork() {
        for (var bad:List.of(new OsrmRoadRouteClient.RoadRequest("181,30","120,30","A","B",null,0),
                new OsrmRoadRouteClient.RoadRequest("120,30","120,30","A","B",List.of("bad"),0),
                new OsrmRoadRouteClient.RoadRequest("120,30","120,30","A","B",List.of("120,30","120,30","120,30","120,30"),0)))
            assertThrows(IllegalArgumentException.class,()->client.quote(bad));
        assertNull(uri.get());
    }
    @Test void missingAlternativeDoesNotPretendFirstRouteIsRequestedAlternative() {
        response="{\"code\":\"Ok\",\"routes\":["+path(12000)+"]}";
        var e=assertThrows(OsrmRoadRouteClient.RoadServiceException.class,()->client.quote(request(1)));
        assertEquals("ALTERNATIVE_UNAVAILABLE",e.code()); assertTrue(e.response().contains("routes"));
    }
    @Test void brokenGeometryIsRejectedRatherThanReplacedWithWaypointLine() {
        response=response.replace("[120.1,30.1]","[999,30.1]");
        var error=assertThrows(OsrmRoadRouteClient.RoadServiceException.class,()->client.quote(request(0)));
        assertEquals("INVALID_RESPONSE",error.code()); assertTrue(error.response().contains("999"));
    }
}
