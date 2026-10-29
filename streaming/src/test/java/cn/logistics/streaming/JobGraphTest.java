package cn.logistics.streaming;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JobGraphTest {
    @Test
    void buildsConsoleOnlyJobBeforeMongoIsAvailable() {
        StreamExecutionEnvironment env = GpsStreamingJob.createEnvironment(new String[0]);
        assertDoesNotThrow(() -> env.getStreamGraph().getJobGraph());
    }

    @Test
    void buildsSerializableJobForOneSlotWithMongoSink() {
        StreamExecutionEnvironment env = GpsStreamingJob.createEnvironment(new String[]{
                "--mongo-uri", "mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=5000"
        });
        assertEquals(1, env.getParallelism());
        assertDoesNotThrow(() -> env.getStreamGraph().getJobGraph());
    }

    @Test
    void rejectsInvalidThresholdBeforeSubmittingToCluster() {
        assertThrows(IllegalArgumentException.class,
                () -> GpsStreamingJob.createEnvironment(new String[]{"--speed-limit-kmh", "NaN"}));
    }
}
