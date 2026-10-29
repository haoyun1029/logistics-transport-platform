package cn.logistics.platform.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.List;
import cn.logistics.platform.domain.FeedbackPlausibility;

/** Online SQL view; Hadoop offline results have a separate endpoint and provenance. */
@Service
public class TransportStatisticsService {
    private static final String VALID_FEEDBACK = "t.task_status='COMPLETED' AND t.completed_at>t.started_at "
            + "AND f.actual_distance_km>0 AND f.actual_fuel_liters>0 AND f.actual_fuel_liters/f.actual_distance_km<=2 "
            + "AND f.actual_distance_km*3600000000.0<=" + FeedbackPlausibility.MAX_AVERAGE_SPEED_KMH
            + "*TIMESTAMPDIFF(MICROSECOND,t.started_at,t.completed_at)";
    private final JdbcTemplate jdbc;
    private final String hdfsStatisticsPath;
    public TransportStatisticsService(JdbcTemplate jdbc,@Value("${app.hdfs.statistics-path}") String path) {this.jdbc=jdbc;this.hdfsStatisticsPath=path;}
    public StatisticsResponse byOd() {
        List<OdStatistics> records=jdbc.query("""
                SELECT r.origin_city,r.destination_city,COUNT(t.id) AS task_count,
                  ROUND(AVG(CASE WHEN %s
                    THEN TIMESTAMPDIFF(MICROSECOND,t.started_at,t.completed_at)/60000000.0 END),2) AS avg_elapsed_minutes,
                  ROUND(AVG(CASE WHEN %s THEN f.actual_distance_km END),2) AS avg_distance_km,
                  ROUND(AVG(CASE WHEN %s THEN f.actual_fuel_liters END),2) AS avg_fuel_liters,
                  COALESCE(SUM(a.anomaly_count),0) AS anomaly_count
                FROM routes r LEFT JOIN transport_tasks t ON t.route_id=r.id
                LEFT JOIN driver_feedback f ON f.task_id=t.id
                LEFT JOIN (SELECT entity_id,SUM(COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(details_json,'$.count')) AS UNSIGNED),1)) AS anomaly_count
                           FROM audit_logs WHERE action='GPS_ANOMALY' GROUP BY entity_id) a ON a.entity_id=t.task_code
                GROUP BY r.origin_city,r.destination_city ORDER BY r.origin_city,r.destination_city
                """.formatted(VALID_FEEDBACK,VALID_FEEDBACK,VALID_FEEDBACK),(rs,n)->new OdStatistics(rs.getString("origin_city"),rs.getString("destination_city"),rs.getInt("task_count"),
                nullable(rs,"avg_elapsed_minutes"),nullable(rs,"avg_distance_km"),nullable(rs,"avg_fuel_liters"),rs.getInt("anomaly_count")));
        return new StatisticsResponse("MYSQL_BUSINESS_TABLES",records,"SEPARATE_HADOOP_JOB",false,hdfsStatisticsPath,
                "在线业务 SQL 查询；异常来自 MySQL 业务审计记录。Hadoop 页面从 HDFS 读取独立离线作业结果，异常使用 MongoDB 去重事件，两者时间与来源不可混加。",
                records.isEmpty()?"NO_MYSQL_TASKS":"MYSQL_TASKS_AVAILABLE");
    }
    private static Double nullable(java.sql.ResultSet rs,String column)throws java.sql.SQLException {double v=rs.getDouble(column);return rs.wasNull()?null:v;}
    public record StatisticsResponse(String source,List<OdStatistics> records,String archiveSource,boolean archiveIncludesTaskStatistics,String archivePath,String note,String dataStatus) {}
    public record OdStatistics(String originCity,String destinationCity,int taskCount,Double averageActualElapsedMinutes,Double averageActualDistanceKm,Double averageActualFuelLiters,int anomalyCount) {}
}

