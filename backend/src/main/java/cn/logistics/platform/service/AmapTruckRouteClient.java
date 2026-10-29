package cn.logistics.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Official logistics v4 API; its response is material for review, never automatic truck approval. */
@Service
public class AmapTruckRouteClient {
    private final RestClient restClient;
    private final String endpoint;
    private final String webServiceKey;
    private final String drivingEndpoint;

    @Autowired
    public AmapTruckRouteClient(RestClient.Builder builder,
            @Value("${app.amap.truck-endpoint:https://restapi.amap.com/v4/direction/truck}") String endpoint,
            @Value("${app.amap.web-service-key:}") String webServiceKey,
            @Value("${app.amap.driving-endpoint:https://restapi.amap.com/v3/direction/driving}") String drivingEndpoint) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(20000);
        this.restClient = builder.requestFactory(factory).build();
        this.endpoint = endpoint;
        this.drivingEndpoint = drivingEndpoint;
        this.webServiceKey = webServiceKey == null ? "" : webServiceKey.trim();
    }

    public AmapTruckRouteClient(RestClient.Builder builder, String endpoint, String key) {
        this(builder, endpoint, key, "https://restapi.amap.com/v3/direction/driving");
    }
    public TruckRouteQuote quote(TruckRouteRequest request) { return quote(request, true); }
    public TruckRouteQuote quoteRoadGeometry(TruckRouteRequest request) { return quote(request, false); }
    private TruckRouteQuote quote(TruckRouteRequest request, boolean truck) {
        if (truck) validate(request); else validateCoordinates(request);
        if (webServiceKey.isBlank() || "replace_me".equals(webServiceKey)) {
            throw new IllegalStateException("未配置高德货车路线 Web 服务 Key，当前只能保存待核验路线。");
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("origin", request.origin().trim());
        parameters.put("destination", request.destination().trim());
        if (truck) {
        parameters.put("strategy", Integer.toString(request.strategy()));
        parameters.put("size", request.vehicleSize());
        parameters.put("load", format(request.loadTons()));
        optional(parameters, "height", request.heightMeters());
        optional(parameters, "width", request.widthMeters());
        optional(parameters, "weight", request.ratedPayloadTons());
        if (request.axleCount() != null) parameters.put("axis", request.axleCount().toString());
        if (!blank(request.plateNumber())) {
            String plate = request.plateNumber().trim();
            parameters.put("province", plate.substring(0, 1));
            parameters.put("number", plate.substring(1));
        }
        parameters.put("showpolyline", "1");
        parameters.put("nosteps", "0");
        } else {
            parameters.put("strategy", "0");
            parameters.put("extensions", "all");
            parameters.put("output", "json");
        }
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(truck ? endpoint : drivingEndpoint).queryParam("key", webServiceKey);
        parameters.forEach(uri::queryParam);
        Instant requestedAt = Instant.now();
        JsonNode response = restClient.get().uri(uri.build().encode().toUri()).retrieve().body(JsonNode.class);
        if (response == null) throw new IllegalStateException("高德货车路线服务返回空响应。");
        if (truck ? !"0".equals(response.path("errcode").asText()) : !"1".equals(response.path("status").asText())) {
            String code = response.path(truck ? "errcode" : "infocode").asText("UNKNOWN");
            String message = response.path(truck ? "errmsg" : "info").asText("unknown");
            String detail = response.path("errdetail").asText("");
            // Remove secrets even if an upstream proxy echoes them in its JSON response.
            String excerpt = response.toString().replace(webServiceKey, "REDACTED");
            throw new MapServiceException(code, (message + " " + detail).trim().replace(webServiceKey, "REDACTED"), excerpt);
        }
        return materialize(request.originCity().trim(), request.destinationCity().trim(), parameters,
                response.toString().replace(webServiceKey, "REDACTED"), response, requestedAt,
                truck ? "AMAP_TRUCK_ROUTE_API_V4" : "AMAP_DRIVING_ROAD_GEOMETRY_V3");
    }

    /** Rebuild a saved material without contacting AMap or consuming another request. */
    public TruckRouteQuote restoreQuote(String originCity, String destinationCity, String parametersJson,
            String rawResponse, Instant requestedAt, String dataSource) {
        if (!List.of("AMAP_TRUCK_ROUTE_API_V4", "AMAP_DRIVING_ROAD_GEOMETRY_V3").contains(dataSource))
            throw new IllegalArgumentException("此历史资料来源暂不支持重新解析，请复核原始返回。");
        try {
            var mapper = new ObjectMapper();
            Map<String, String> parameters = mapper.readValue(parametersJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            return materialize(originCity, destinationCity, parameters, rawResponse,
                    mapper.readTree(rawResponse), requestedAt, dataSource);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("保存的道路资料 JSON 无法解析。", exception);
        }
    }

    private static TruckRouteQuote materialize(String originCity, String destinationCity,
            Map<String, String> parameters, String rawResponse, JsonNode response, Instant requestedAt, String dataSource) {
        boolean truck = "AMAP_TRUCK_ROUTE_API_V4".equals(dataSource);
        JsonNode path = (truck ? response.path("data") : response).path("route").path("paths").path(0);
        if (path.isMissingNode()) throw new IllegalStateException("高德货车路线响应缺少可用路径。");
        double distanceKm = parsePositive(path.path("distance").asText(), "distance") / 1000;
        double durationMinutes = parsePositive(path.path("duration").asText(), "duration") / 60;
        GeometryPreview preview = geometry(path.path("steps"));
        return new TruckRouteQuote(parameters.get("origin"), parameters.get("destination"), originCity,
                destinationCity, Map.copyOf(parameters), distanceKm, durationMinutes,
                rawResponse, requestedAt, truck ? "PENDING_TRUCK_EVIDENCE" : "PENDING_PUBLIC_TRUCK_EVIDENCE",
                dataSource, preview.geometry(), preview.roads(), truck ? path.path("restriction").asInt(0) : 0,
                preview.status(), preview.steps(), preview.issues());
    }

    private static GeometryPreview geometry(JsonNode steps) {
        var coordinates = new ArrayList<List<Double>>();
        var roads = new LinkedHashSet<String>();
        var sections = new ArrayList<GeometryStep>();
        var issues = new ArrayList<GeometryIssue>();
        if (!steps.isArray() || steps.isEmpty()) return new GeometryPreview(null, List.of(), "MISSING_STEPS", List.of(), List.of());
        boolean complete = true;
        List<Double> previousEnd = null;
        int index = 0;
        for (JsonNode step : steps) {
            int number = ++index;
            String road = step.path("road").asText("").trim();
            if (!road.isBlank()) roads.add(road);
            String[] points = step.path("polyline").asText("").split(";", -1);
            var section = new ArrayList<List<Double>>();
            boolean valid = points.length >= 2;
            if (valid) for (String text : points) {
                try { section.add(coordinate(text)); }
                catch (IllegalArgumentException e) { valid = false; break; }
            }
            sections.add(new GeometryStep(number, road, step.path("instruction").asText(""),
                    valid ? List.copyOf(section) : List.of()));
            if (!valid) {
                complete = false;
                issues.add(new GeometryIssue(points.length < 2 ? "MISSING_GEOMETRY" : "INVALID_GEOMETRY",
                        number, null, null, null, null));
                previousEnd = null;
                continue;
            }
            var start = section.getFirst();
            if (previousEnd != null && (Math.abs(previousEnd.get(0) - start.get(0)) > 0.000001
                    || Math.abs(previousEnd.get(1) - start.get(1)) > 0.000001)) {
                complete = false;
                issues.add(new GeometryIssue("DISCONNECTED", number, number - 1,
                        distanceMeters(previousEnd, start), previousEnd, start));
            }
            previousEnd = section.getLast();
            for (var point : section) if (coordinates.isEmpty() || !coordinates.getLast().equals(point)) coordinates.add(point);
        }
        // A missing/broken step must not become an invented straight connector in the saved geometry.
        return new GeometryPreview(complete && coordinates.size() >= 2 ? new RouteGeometry("GCJ02", List.copyOf(coordinates)) : null,
                List.copyOf(roads), complete ? "COMPLETE_PENDING_REVIEW" : "INCOMPLETE_OR_DISCONNECTED",
                List.copyOf(sections), List.copyOf(issues));
    }

    private static double distanceMeters(List<Double> a, List<Double> b) {
        double dlat = Math.toRadians(b.get(1) - a.get(1)), dlon = Math.toRadians(b.get(0) - a.get(0));
        double h = Math.pow(Math.sin(dlat / 2), 2) + Math.cos(Math.toRadians(a.get(1)))
                * Math.cos(Math.toRadians(b.get(1))) * Math.pow(Math.sin(dlon / 2), 2);
        return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
    }

    private static List<Double> coordinate(String text) {
        if (text == null || !text.trim().matches("-?\\d{1,3}(?:\\.\\d{1,6})?,-?\\d{1,2}(?:\\.\\d{1,6})?"))
            throw new IllegalArgumentException("坐标须为经度,纬度，最多六位小数。");
        String[] values = text.trim().split(",");
        double lon = Double.parseDouble(values[0]), lat = Double.parseDouble(values[1]);
        if (lon < -180 || lon > 180 || lat < -90 || lat > 90) throw new IllegalArgumentException("经纬度超出范围。");
        return List.of(lon, lat);
    }

    private static double parsePositive(String value, String field) {
        try { double parsed = Double.parseDouble(value); if (parsed > 0 && Double.isFinite(parsed)) return parsed; }
        catch (NumberFormatException ignored) { }
        throw new IllegalStateException("高德响应中的 " + field + " 无效。");
    }
    private static String format(double value) { return String.format(java.util.Locale.ROOT, "%.3f", value); }
    private static void optional(Map<String, String> parameters, String name, Double value) {
        if (value != null) parameters.put(name, format(value));
    }
    private static void range(Double value, double min, double max, String name) {
        if (value != null && (!Double.isFinite(value) || value < min || value > max))
            throw new IllegalArgumentException(name + "参数超出范围。");
    }
    private static void validateCoordinates(TruckRouteRequest r) {
        if (r == null || blank(r.originCity()) || blank(r.destinationCity())) throw new IllegalArgumentException("起终点地级市名称不能为空。");
        coordinate(r.origin()); coordinate(r.destination());
    }
    private static void validate(TruckRouteRequest r) {
        validateCoordinates(r);
        if (r.strategy() < 1 || r.strategy() > 13 || r.vehicleSize() == null || !r.vehicleSize().matches("[1-4]"))
            throw new IllegalArgumentException("策略须为 1–13，车型须为 1–4。");
        range(r.loadTons(), 0, 100, "车辆总质量（吨）");
        range(r.heightMeters(), 0, 25.5, "车高（米）");
        range(r.widthMeters(), 0, 25.5, "车宽（米）");
        range(r.ratedPayloadTons(), 0, 99.999, "核定载重（吨）");
        if (r.axleCount() != null && (r.axleCount() < 0 || r.axleCount() > 255)) throw new IllegalArgumentException("轴数须为 0–255。");
        if (!blank(r.plateNumber()) && !r.plateNumber().trim().matches("[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼][A-Z0-9]{6,7}"))
            throw new IllegalArgumentException("车牌须为省份简称和 6–7 位大写字母数字。");
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record TruckRouteRequest(String origin, String destination, String originCity, String destinationCity,
            int strategy, String vehicleSize, double loadTons, String plateNumber,
            Double heightMeters, Double widthMeters, Double ratedPayloadTons, Integer axleCount) {
        public TruckRouteRequest(String origin, String destination, String originCity, String destinationCity,
                int strategy, String vehicleSize, double loadTons, String plateNumber) {
            this(origin, destination, originCity, destinationCity, strategy, vehicleSize, loadTons, plateNumber, null, null, null, null);
        }
    }
    public record RouteGeometry(String coordinateSystem, List<List<Double>> coordinates) { }
    public record GeometryStep(int number, String roadName, String instruction, List<List<Double>> coordinates) { }
    public record GeometryIssue(String type, int stepNumber, Integer previousStepNumber, Double distanceMeters,
                                List<Double> from, List<Double> to) { }
    private record GeometryPreview(RouteGeometry geometry, List<String> roads, String status,
                                   List<GeometryStep> steps, List<GeometryIssue> issues) { }
    public record TruckRouteQuote(String origin, String destination, String originCity, String destinationCity,
            Map<String, String> requestParameters, double distanceKm, double durationMinutes, String rawResponse,
            Instant requestedAt, String verificationStatus, String dataSource, RouteGeometry geometry,
            List<String> roadNames, int restriction, String geometryStatus,
            List<GeometryStep> steps, List<GeometryIssue> geometryIssues) { }
    public static class MapServiceException extends IllegalStateException {
        private final String code;
        private final String responseExcerpt;
        public MapServiceException(String code, String message, String responseExcerpt) {
            super("高德路线服务未返回成功结果: " + code + " / " + message);
            this.code = code; this.responseExcerpt = responseExcerpt;
        }
        public String code() { return code; }
        public String responseExcerpt() { return responseExcerpt; }
    }
}
