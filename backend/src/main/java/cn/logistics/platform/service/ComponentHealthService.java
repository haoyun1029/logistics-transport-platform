package cn.logistics.platform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;

/** Lightweight runtime checks for the services used by the demonstration topology. */
@Service
public class ComponentHealthService {

    private static final int TIMEOUT_MILLIS = 1200;
    private final RestClient restClient;
    private final String host;
    private final int kafkaPort;
    private final int flinkPort;
    private final int mysqlPort;
    private final int hdfsPort;
    private final MongoTelemetryService telemetry;

    public ComponentHealthService(RestClient.Builder builder, MongoTelemetryService telemetry,
                                  @Value("${app.services.host:192.168.146.128}") String host,
                                  @Value("${app.services.kafka-port:9092}") int kafkaPort,
                                  @Value("${app.services.flink-port:8081}") int flinkPort,
                                  @Value("${app.services.mysql-port:3306}") int mysqlPort,
                                  @Value("${app.services.hdfs-port:8020}") int hdfsPort) {
        this.restClient = builder.requestFactory(requestFactory()).build();
        this.host = host;
        this.kafkaPort = kafkaPort;
        this.flinkPort = flinkPort;
        this.mysqlPort = mysqlPort;
        this.hdfsPort = hdfsPort;
        this.telemetry = telemetry;
    }

    public List<ComponentStatus> checkAll() {
        return List.of(
                tcp("Kafka", host, kafkaPort),
                flink(),
                tcp("MySQL", host, mysqlPort),
                mongo(),
                tcp("HDFS NameNode", host, hdfsPort));
    }

    private ComponentStatus mongo() {
        var health = telemetry.connectionHealth();
        return new ComponentStatus("MongoDB", health.endpoint(), health.status(), health.detail());
    }

    private ComponentStatus flink() {
        String endpoint = "http://" + host + ":" + flinkPort;
        try {
            restClient.get().uri(endpoint + "/overview").retrieve().toBodilessEntity();
            return new ComponentStatus("Flink", host + ":" + flinkPort, "CONNECTED", "REST /overview 可访问");
        } catch (RuntimeException exception) {
            return new ComponentStatus("Flink", host + ":" + flinkPort, "UNAVAILABLE", "REST /overview 不可访问");
        }
    }

    private ComponentStatus tcp(String name, String address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), TIMEOUT_MILLIS);
            return new ComponentStatus(name, address + ":" + port, "CONNECTED", "TCP 连接成功");
        } catch (Exception exception) {
            return new ComponentStatus(name, address + ":" + port, "UNAVAILABLE", "TCP 连接失败");
        }
    }

    private static org.springframework.http.client.ClientHttpRequestFactory requestFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(TIMEOUT_MILLIS));
        factory.setReadTimeout(Duration.ofMillis(TIMEOUT_MILLIS));
        return factory;
    }

    public record ComponentStatus(String name, String endpoint, String status, String detail) {}
}
