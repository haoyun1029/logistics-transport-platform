package cn.logistics.platform.repository;

import cn.logistics.platform.service.AmapTruckRouteClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persists both successful and failed map verification attempts for audit and review. */
@Repository
public class RouteVerificationAttemptRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private volatile boolean tableEnsured;

    public RouteVerificationAttemptRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public long insertFailure(AmapTruckRouteClient.TruckRouteRequest request,
                              long requestedBy, String responseStatus,
                              String failureReason, String responseExcerpt) {
        return insertFailure(request, requestedBy, responseStatus, failureReason, responseExcerpt, Map.of());
    }

    @Transactional
    public long insertFailure(AmapTruckRouteClient.TruckRouteRequest request,
                              long requestedBy, String responseStatus, String failureReason,
                              String responseExcerpt, Map<String, ?> extraParameters) {
        ensureTable();
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (request != null) {
            parameters.put("strategy", request.strategy());
            parameters.put("vehicleSize", request.vehicleSize());
            parameters.put("loadTons", request.loadTons());
            parameters.put("heightMeters", request.heightMeters());
            parameters.put("widthMeters", request.widthMeters());
            parameters.put("ratedPayloadTons", request.ratedPayloadTons());
            parameters.put("axleCount", request.axleCount());
            if (request.plateNumber() != null && !request.plateNumber().isBlank()) {
                parameters.put("plateNumber", request.plateNumber().trim());
            }
        }
        parameters.putAll(extraParameters);
        String sql = """
                INSERT INTO route_verification_attempts (
                    origin_city, destination_city, origin_coordinate, destination_coordinate,
                    request_parameters_json, response_status, response_excerpt,
                    verification_status, failure_reason, requested_by, requested_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'FAILED', ?, ?, ?)
                """;
        jdbc.update(sql,
                value(request == null ? null : request.originCity()),
                value(request == null ? null : request.destinationCity()),
                value(request == null ? null : request.origin()),
                value(request == null ? null : request.destination()),
                json(parameters),
                truncate(responseStatus, 48),
                truncate(responseExcerpt, 1200),
                truncate(failureReason, 1000),
                requestedBy,
                java.sql.Timestamp.from(Instant.now()));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (id == null) throw new IllegalStateException("未能读取路线核验失败记录编号。");
        jdbc.update("""
                INSERT INTO audit_logs (actor_user_id, action, entity_type, entity_id, details_json)
                VALUES (?, 'TRUCK_ROUTE_VERIFICATION_FAILED', 'ROUTE_VERIFICATION_ATTEMPT', ?, ?)
                """, requestedBy, id.toString(), json(Map.of(
                "responseStatus", truncate(responseStatus, 48),
                "failureReason", truncate(failureReason, 1000),
                "verificationStatus", "FAILED")));
        return id;
    }

    public List<AttemptView> findRecent(int limit) {
        ensureTable();
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("""
                SELECT id, origin_city, destination_city, origin_coordinate, destination_coordinate,
                       response_status, response_excerpt, verification_status, failure_reason, requested_by, requested_at
                FROM route_verification_attempts
                ORDER BY requested_at DESC, id DESC
                LIMIT ?
                """, (rs, rowNum) -> new AttemptView(
                rs.getLong("id"), rs.getString("origin_city"), rs.getString("destination_city"),
                rs.getString("origin_coordinate"), rs.getString("destination_coordinate"),
                rs.getString("response_status"), rs.getString("response_excerpt"),
                rs.getString("verification_status"), rs.getString("failure_reason"),
                rs.getLong("requested_by"), rs.getTimestamp("requested_at").toInstant()), safeLimit);
    }

    private void ensureTable() {
        if (tableEnsured) return;
        synchronized (this) {
            if (tableEnsured) return;
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS route_verification_attempts (
                        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        origin_city VARCHAR(80),
                        destination_city VARCHAR(80),
                        origin_coordinate VARCHAR(96),
                        destination_coordinate VARCHAR(96),
                        request_parameters_json JSON NOT NULL,
                        response_status VARCHAR(48) NOT NULL,
                        response_excerpt VARCHAR(1200),
                        verification_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                        failure_reason VARCHAR(1000) NOT NULL,
                        requested_by BIGINT NOT NULL,
                        requested_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        INDEX idx_route_attempts_od_time (origin_city, destination_city, requested_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            tableEnsured = true;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("路线核验请求无法编码为 JSON。", exception);
        }
    }

    private static String value(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String truncate(String text, int max) {
        if (text == null) return null;
        String normalized = text.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    public record AttemptView(long id, String originCity, String destinationCity,
                              String originCoordinate, String destinationCoordinate,
                              String responseStatus, String responseExcerpt,
                              String verificationStatus, String failureReason,
                              long requestedBy, Instant requestedAt) {}
}
