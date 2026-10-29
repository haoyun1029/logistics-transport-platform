package cn.logistics.platform.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class HdfsStatisticsServiceTest {
    HttpServer server;boolean success=true;
    final String base="/logistics/transport-statistics/mapreduce/run=test/";
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/webhdfs/v1/",exchange->{
            String path=exchange.getRequestURI().getPath();String body;int status=200;
            if(path.endsWith("latest.json"))body="{\"schemaVersion\":1,\"source\":\"HADOOP_MAPREDUCE\",\"resultPath\":\""+base+"output/part-00000\",\"successPath\":\""+base+"output/_SUCCESS\",\"runId\":\"test\",\"jobId\":\"local-test\"}";
            else if(path.endsWith("_SUCCESS")){body="{}";status=success?200:404;}
            else body="{\"originCity\":\"A\",\"destinationCity\":\"B\",\"taskCount\":2,\"completedFeedbackCount\":1,\"averageActualElapsedMinutes\":25.5,\"averageActualDistanceKm\":30,\"averageActualFuelLiters\":9,\"anomalyCount\":3,\"excludedSampleCount\":0,\"simulatedTaskCount\":2}\n";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    HdfsStatisticsService service(){return new HdfsStatisticsService(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"/logistics/transport-statistics/latest.json",9864);}
    @Test void readsCompletedHadoopOutputWithActualProvenance(){var result=service().statistics();assertEquals("HADOOP_RESULT_AVAILABLE",result.dataStatus());assertEquals("local-test",result.jobId());assertEquals(25.5,result.records().getFirst().averageActualElapsedMinutes());}
    @Test void missingSuccessMarkerNeverExposesPartialStatistics(){success=false;var result=service().statistics();assertEquals("HDFS_RESULT_UNAVAILABLE",result.dataStatus());assertTrue(result.records().isEmpty());assertFalse(result.archiveIncludesTaskStatistics());}
}
