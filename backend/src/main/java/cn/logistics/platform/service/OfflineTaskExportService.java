package cn.logistics.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;

@Service
public class OfflineTaskExportService {
    private final JdbcTemplate jdbc;
    private final MongoTelemetryService telemetry;
    private final ObjectMapper json;
    public OfflineTaskExportService(JdbcTemplate jdbc,MongoTelemetryService telemetry,ObjectMapper json) {this.jdbc=jdbc;this.telemetry=telemetry;this.json=json;}
    public String rawTasks() {
        var tasks=jdbc.queryForList("""
                SELECT t.task_code,t.task_status,t.source_type,t.sample_status,t.started_at,t.completed_at,
                       r.origin_city,r.destination_city,f.actual_distance_km,f.actual_fuel_liters
                FROM transport_tasks t JOIN routes r ON r.id=t.route_id
                LEFT JOIN driver_feedback f ON f.task_id=t.id ORDER BY t.id
                """);
        var lines=new StringBuilder();
        for(var task:tasks) {
            var summary=telemetry.summary(task.get("task_code").toString());
            if(!"CONNECTED".equals(summary.status()))throw new IllegalStateException("MongoDB 不可用，未导出不完整的异常统计。");
            var value=new LinkedHashMap<String,Object>();
            value.put("schemaVersion",1);value.put("taskCode",task.get("task_code"));
            value.put("originCity",task.get("origin_city"));value.put("destinationCity",task.get("destination_city"));
            value.put("status",task.get("task_status"));value.put("sourceType",task.get("source_type"));value.put("sampleStatus",task.get("sample_status"));
            var start=(java.sql.Timestamp)task.get("started_at");var end=(java.sql.Timestamp)task.get("completed_at");
            value.put("startedAt",start==null?null:start.toInstant().toString());value.put("completedAt",end==null?null:end.toInstant().toString());
            value.put("actualElapsedMinutes",start==null||end==null?null:(end.getTime()-start.getTime())/60000.0);
            value.put("actualDistanceKm",task.get("actual_distance_km"));value.put("actualFuelLiters",task.get("actual_fuel_liters"));
            value.put("anomalyCount",summary.anomalyCount());
            try {lines.append(json.writeValueAsString(value)).append('\n');}catch(Exception e){throw new IllegalStateException("Cannot export raw task",e);}
        }
        return lines.toString();
    }
}
