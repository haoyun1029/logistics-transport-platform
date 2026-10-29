package cn.logistics.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.*;

/** Low-volume public OSRM/OSM road material. The car profile never proves truck access. */
@Service
public class OsrmRoadRouteClient {
    public static final String SOURCE = "OSRM_OSM_DRIVING_V5";
    private final RestClient client;
    private final String endpoint;
    private final ObjectMapper mapper = new ObjectMapper();
    private long lastRequestNanos;

    public OsrmRoadRouteClient(RestClient.Builder builder,
            @Value("${app.osrm.endpoint:https://router.project-osrm.org/route/v1/driving}") String endpoint) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000); factory.setReadTimeout(20000);
        client = builder.requestFactory(factory).defaultHeader("User-Agent", "LogisticsGraduationDemo/0.1 (low-volume road review)").build();
        this.endpoint = endpoint;
    }

    public synchronized AmapTruckRouteClient.TruckRouteQuote quote(RoadRequest request) {
        Map<String, String> parameters = parameters(request);
        long now = System.nanoTime();
        if (lastRequestNanos != 0 && now - lastRequestNanos < 1_100_000_000L)
            throw new IllegalStateException("公共道路服务最多每秒一次，请稍后再试；优先复用已保存资料。");
        var locations = new ArrayList<String>(); locations.add(parameters.get("origin"));
        if (request.via() != null) request.via().forEach(point -> locations.add(point.trim()));
        locations.add(parameters.get("destination"));
        var uri = UriComponentsBuilder.fromUriString(endpoint).pathSegment(String.join(";", locations))
                .queryParam("overview", "full").queryParam("geometries", "geojson")
                .queryParam("steps", "true").queryParam("alternatives", "true");
        Instant requestedAt = Instant.now(); lastRequestNanos = now;
        JsonNode response = client.get().uri(uri.build().encode().toUri()).retrieve().body(JsonNode.class);
        if (response == null) throw new IllegalStateException("OSRM 道路服务返回空响应。");
        if (!"Ok".equals(response.path("code").asText()))
            throw new RoadServiceException(response.path("code").asText("UNKNOWN"), response.toString());
        try { return materialize(request.originCity().trim(), request.destinationCity().trim(), parameters, response, requestedAt); }
        catch (RoadServiceException e) { throw e; }
        catch (IllegalStateException e) { throw new RoadServiceException("INVALID_RESPONSE", response.toString()); }
    }

    public AmapTruckRouteClient.TruckRouteQuote restoreQuote(String originCity, String destinationCity,
            String parametersJson, String rawResponse, Instant requestedAt) {
        try {
            Map<String, String> parameters = mapper.readValue(parametersJson, new TypeReference<>() {});
            return materialize(originCity, destinationCity, parameters, mapper.readTree(rawResponse), requestedAt);
        } catch (JsonProcessingException e) { throw new IllegalArgumentException("保存的 OSRM 道路资料无法解析。", e); }
    }

    private static Map<String, String> parameters(RoadRequest r) {
        if (r == null || blank(r.originCity()) || blank(r.destinationCity())) throw new IllegalArgumentException("起终点地级市不能为空。");
        validateCoordinate(r.origin()); validateCoordinate(r.destination());
        if (r.via() != null) {
            if (r.via().size() > 3) throw new IllegalArgumentException("最多指定三个道路途经坐标。");
            r.via().forEach(OsrmRoadRouteClient::validateCoordinate);
        }
        if (r.routeIndex() < 0 || r.routeIndex() > 2) throw new IllegalArgumentException("返回路线序号须为 0–2。");
        var parameters = new LinkedHashMap<String, String>();
        parameters.put("origin", r.origin().trim()); parameters.put("destination", r.destination().trim());
        parameters.put("via", r.via() == null ? "" : String.join(";", r.via().stream().map(String::trim).toList()));
        parameters.put("routeIndex", Integer.toString(r.routeIndex())); parameters.put("coordinateSystem", "WGS84");
        parameters.put("profile", "driving"); parameters.put("overview", "full"); parameters.put("geometries", "geojson");
        parameters.put("steps", "true"); parameters.put("alternatives", "true");
        return parameters;
    }

    private static AmapTruckRouteClient.TruckRouteQuote materialize(String originCity, String destinationCity,
            Map<String, String> parameters, JsonNode response, Instant time) {
        int index;
        try { index = Integer.parseInt(parameters.getOrDefault("routeIndex", "0")); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("保存的路线序号无效。"); }
        JsonNode route = response.path("routes").path(index);
        if (route.isMissingNode()) throw new RoadServiceException("ALTERNATIVE_UNAVAILABLE", response.toString());
        double distance = positive(route.path("distance")), duration = positive(route.path("duration"));
        var geometry = coordinates(route.path("geometry"));
        if (geometry.size() < 2) throw new RoadServiceException("INVALID_GEOMETRY", response.toString());
        var steps = new ArrayList<AmapTruckRouteClient.GeometryStep>();
        var roads = new LinkedHashSet<String>();
        for (JsonNode leg : route.path("legs")) for (JsonNode step : leg.path("steps")) {
            String name = step.path("name").asText("");
            if (!name.isBlank()) roads.add(name);
            var points = coordinates(step.path("geometry"));
            // Arrival instructions may be a single point; the full route GeoJSON is authoritative.
            if (points.size() >= 2) steps.add(new AmapTruckRouteClient.GeometryStep(steps.size() + 1, name,
                    step.path("maneuver").path("type").asText(""), points));
        }
        if (steps.isEmpty()) steps.add(new AmapTruckRouteClient.GeometryStep(1, "", "", geometry));
        return new AmapTruckRouteClient.TruckRouteQuote(parameters.get("origin"), parameters.get("destination"),
                originCity, destinationCity, Map.copyOf(parameters), distance / 1000, duration / 60,
                response.toString(), time, "PENDING_PUBLIC_TRUCK_EVIDENCE", SOURCE,
                new AmapTruckRouteClient.RouteGeometry("WGS84", geometry), List.copyOf(roads), 0,
                "COMPLETE_PENDING_REVIEW", List.copyOf(steps), List.of());
    }

    private static List<List<Double>> coordinates(JsonNode geometry) {
        if (!"LineString".equals(geometry.path("type").asText()) || !geometry.path("coordinates").isArray())
            throw new IllegalStateException("OSRM 返回的道路几何类型无效。");
        var result = new ArrayList<List<Double>>();
        if (geometry.path("coordinates").size() > 20000) throw new IllegalStateException("道路点数过多，请缩短到相邻城市路段。");
        for (JsonNode pair : geometry.path("coordinates")) {
            if (!pair.isArray() || pair.size() != 2 || !pair.get(0).isNumber() || !pair.get(1).isNumber())
                throw new IllegalStateException("OSRM 返回的道路坐标无效。");
            double lon = pair.get(0).asDouble(), lat = pair.get(1).asDouble();
            if (!Double.isFinite(lon) || !Double.isFinite(lat) || Math.abs(lon) > 180 || Math.abs(lat) > 90)
                throw new IllegalStateException("OSRM 返回的道路坐标超出范围。");
            result.add(List.of(lon, lat));
        }
        return List.copyOf(result);
    }

    private static void validateCoordinate(String value) {
        if (value == null || !value.trim().matches("-?\\d{1,3}(?:\\.\\d{1,6})?,-?\\d{1,2}(?:\\.\\d{1,6})?"))
            throw new IllegalArgumentException("道路坐标使用 WGS84 经度,纬度，最多六位小数。");
        String[] pair = value.trim().split(",");
        if (Math.abs(Double.parseDouble(pair[0])) > 180 || Math.abs(Double.parseDouble(pair[1])) > 90)
            throw new IllegalArgumentException("道路坐标超出范围。");
    }
    private static double positive(JsonNode value) {
        double number = value.asDouble(Double.NaN);
        if (!Double.isFinite(number) || number <= 0) throw new IllegalStateException("OSRM 返回的里程或耗时无效。");
        return number;
    }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
    public record RoadRequest(String origin, String destination, String originCity, String destinationCity,
                              List<String> via, int routeIndex) {
        public AmapTruckRouteClient.TruckRouteRequest auditRequest() {
            return new AmapTruckRouteClient.TruckRouteRequest(origin, destination, originCity, destinationCity, 0, null, 0, null);
        }
    }
    public static class RoadServiceException extends IllegalStateException {
        private final String code, response;
        public RoadServiceException(String code, String response) {
            super("OSRM 道路资料未取得可用结果：" + code); this.code = code; this.response = response;
        }
        public String code() { return code; }
        public String response() { return response; }
    }
}
