package cn.logistics.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Reads only successful immutable Hadoop outputs via the VM's WebHDFS. No SQL fallback. */
@Service
public class HdfsStatisticsService {
    private final ObjectMapper json;
    private final URI endpoint;
    private final String manifestPath;
    private final int dataPort;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public HdfsStatisticsService(ObjectMapper json,
            @Value("${app.hdfs.web-url:http://192.168.146.128:9870}") String endpoint,
            @Value("${app.hdfs.latest-manifest:/logistics/transport-statistics/latest.json}") String manifestPath,
            @Value("${app.hdfs.data-http-port:9864}") int dataPort) {
        this.json=json;this.endpoint=URI.create(endpoint);this.manifestPath=manifestPath;this.dataPort=dataPort;
    }
    public Result statistics() {
        try {
            JsonNode manifest=json.readTree(read(manifestPath,"OPEN"));
            if(manifest.path("schemaVersion").asInt()!=1 || !"HADOOP_MAPREDUCE".equals(manifest.path("source").asText()))throw new IllegalStateException("Invalid Hadoop manifest");
            String result=manifest.path("resultPath").asText(),success=manifest.path("successPath").asText();
            read(success,"GETFILESTATUS");
            var rows=new ArrayList<OdRecord>();
            for(String line:read(result,"OPEN").split("\\R"))if(!line.isBlank())rows.add(json.readValue(line,OdRecord.class));
            return new Result("HADOOP_MAPREDUCE",List.copyOf(rows),"HDFS_RAW_TASKS_AND_HADOOP_OUTPUT",true,result,
                    "Hadoop 在 HDFS 原始逐任务数据上执行 Map、Shuffle、Reduce；任务数包含全部状态，平均值仅计已完成且反馈数值有效的任务。轨迹匹配待定或排除优化不会伪装成路线优化有效样本。异常次数为 MongoDB 去重异常事件数。",
                    rows.isEmpty()?"NO_TASKS":"HADOOP_RESULT_AVAILABLE",manifest.path("runId").asText(),manifest.path("jobId").asText(),manifest.path("completedAt").asText(),manifest.path("inputPath").asText(),manifest.path("inputSha256").asText(),manifest.path("executionMode").asText());
        } catch(Exception e) {
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            return new Result("HADOOP_MAPREDUCE",List.of(),"HDFS_RAW_TASKS_AND_HADOOP_OUTPUT",false,manifestPath,
                    "暂未读到完整 Hadoop 离线结果，请运行离线统计脚本并检查 HDFS。此处不会用 MySQL 汇总或演示数字替代。",
                    "HDFS_RESULT_UNAVAILABLE",null,null,null,null,null,null);
        }
    }
    private String read(String path,String op)throws Exception {
        if(!path.startsWith("/logistics/transport-statistics/") || path.contains("..") || !path.matches("/[A-Za-z0-9/._=:-]+"))throw new IllegalArgumentException("Invalid HDFS path");
        URI uri=URI.create(endpoint.toString().replaceAll("/$","")+"/webhdfs/v1"+path+"?op="+op+"&user.name=root");
        var response=send(uri);
        if(response.statusCode()==307) {
            URI location=URI.create(response.headers().firstValue("location").orElseThrow());
            response.body().close();
            if(!"http".equals(location.getScheme()) || location.getPort()!=dataPort || !location.getPath().startsWith("/webhdfs/v1/logistics/transport-statistics/"))throw new IllegalStateException("Unexpected WebHDFS redirect");
            // VM DataNode may advertise hadoop101, which Windows cannot resolve.
            response=send(new URI("http",null,endpoint.getHost(),dataPort,location.getPath(),location.getQuery(),null));
        }
        if(response.statusCode()!=200) {response.body().close();throw new IllegalStateException("HDFS read failed");}
        try(var stream=response.body()) {
            byte[] body=stream.readNBytes(1_048_577);
            if(body.length>1_048_576)throw new IllegalStateException("HDFS result exceeds limit");
            return new String(body,java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private HttpResponse<java.io.InputStream> send(URI uri)throws Exception {
        return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
    }
    public record OdRecord(String originCity,String destinationCity,long taskCount,long completedFeedbackCount,
            Double averageActualElapsedMinutes,Double averageActualDistanceKm,Double averageActualFuelLiters,
            long anomalyCount,long excludedSampleCount,long simulatedTaskCount) {}
    public record Result(String source,List<OdRecord> records,String archiveSource,boolean archiveIncludesTaskStatistics,
            String archivePath,String note,String dataStatus,String runId,String jobId,String completedAt,String inputPath,String inputSha256,String executionMode) {}
}
