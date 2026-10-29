package cn.logistics.platform.repository;

import cn.logistics.platform.service.AmapTruckRouteClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.time.Instant;

@Repository
public class RouteQuoteRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RouteQuoteRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public long insert(AmapTruckRouteClient.TruckRouteQuote quote, long requestedBy) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String parameters = json(quote.requestParameters());
        String response = rawJson(quote.rawResponse());
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO route_quotes (
                        origin_city, destination_city, origin_coordinate, destination_coordinate,
                        request_parameters_json, response_json, distance_km, duration_minutes,
                        data_source, truck_access_status, requested_by, requested_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, quote.originCity());
            statement.setString(2, quote.destinationCity());
            statement.setString(3, quote.origin());
            statement.setString(4, quote.destination());
            statement.setString(5, parameters);
            statement.setString(6, response);
            statement.setDouble(7, quote.distanceKm());
            statement.setDouble(8, quote.durationMinutes());
            statement.setString(9, quote.dataSource());
            statement.setLong(10, requestedBy);
            statement.setTimestamp(11, java.sql.Timestamp.from(quote.requestedAt()));
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) throw new IllegalStateException("未能读取货车路线报价编号。");
        jdbc.update("""
                INSERT INTO audit_logs (actor_user_id, action, entity_type, entity_id, details_json)
                VALUES (?, ?, 'ROUTE_QUOTE', ?, ?)
                """, requestedBy, quote.dataSource().contains("TRUCK") ? "TRUCK_ROUTE_QUOTED" : "ROAD_GEOMETRY_QUOTED", id.toString(), json(Map.of(
                "originCity", quote.originCity(), "destinationCity", quote.destinationCity(),
                "distanceKm", quote.distanceKm(), "durationMinutes", quote.durationMinutes(),
                "truckAccessStatus", "PENDING")));
        return id.longValue();
    }

    public List<QuoteView> findRecent(int limit) {
        return jdbc.query("""
                SELECT id, origin_city, destination_city, request_parameters_json, response_json,
                       distance_km, duration_minutes, data_source, truck_access_status, requested_at
                FROM route_quotes ORDER BY requested_at DESC, id DESC LIMIT ?
                """, (rs, row) -> new QuoteView(rs.getLong("id"), rs.getString("origin_city"),
                rs.getString("destination_city"), rs.getString("request_parameters_json"), rs.getString("response_json"),
                rs.getDouble("distance_km"), rs.getDouble("duration_minutes"), rs.getString("data_source"),
                rs.getString("truck_access_status"), rs.getTimestamp("requested_at").toInstant()), Math.max(1, Math.min(limit, 100)));
    }

    public Optional<QuoteView> findById(long id) {
        return jdbc.query("""
                SELECT id, origin_city, destination_city, request_parameters_json, response_json,
                       distance_km, duration_minutes, data_source, truck_access_status, requested_at
                FROM route_quotes WHERE id = ?
                """, (rs, row) -> new QuoteView(rs.getLong("id"), rs.getString("origin_city"),
                rs.getString("destination_city"), rs.getString("request_parameters_json"), rs.getString("response_json"),
                rs.getDouble("distance_km"), rs.getDouble("duration_minutes"), rs.getString("data_source"),
                rs.getString("truck_access_status"), rs.getTimestamp("requested_at").toInstant()), id).stream().findFirst();
    }

    public record QuoteView(long id, String originCity, String destinationCity, String requestParametersJson,
            String responseJson, double distanceKm, double durationMinutes, String dataSource,
            String truckAccessStatus, Instant requestedAt) { }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("路线报价数据无法编码为 JSON。", exception);
        }
    }

    private String rawJson(String value) {
        try {
            return objectMapper.readTree(value).toString();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("高德路线响应不是有效 JSON。", exception);
        }
    }
}
