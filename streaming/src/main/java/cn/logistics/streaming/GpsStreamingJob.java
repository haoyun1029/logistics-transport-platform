package cn.logistics.streaming;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.flink.api.common.state.*;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class GpsStreamingJob {
    public static void main(String[] args) throws Exception {
        createEnvironment(args).execute("Logistics simulated GPS validation and sustained deviation (no road matching)");
    }

    static StreamExecutionEnvironment createEnvironment(String[] args) {
        ParameterTool options = ParameterTool.fromArgs(args);
        double speedLimit = options.getDouble("speed-limit-kmh", 80);
        new GpsEventProcessor(speedLimit);
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1); // Existing VM has exactly one available slot.
        env.enableCheckpointing(10000, CheckpointingMode.AT_LEAST_ONCE);
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(3, 5000));
        if (options.has("checkpoint-dir")) {
            env.getCheckpointConfig().setCheckpointStorage(options.get("checkpoint-dir"));
        }
        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(options.get("bootstrap-servers", "192.168.146.128:9092"))
                .setTopics(options.get("topic", "logistics.gps.raw"))
                .setGroupId(options.get("group-id", "logistics-gps-flink-v1"))
                .setStartingOffsets(OffsetsInitializer.committedOffsets(OffsetResetStrategy.EARLIEST))
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();
        org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator<String> processed = env
                .fromSource(source, WatermarkStrategy.noWatermarks(), "Kafka raw simulated GPS").uid("gps-kafka-source")
                .map(new ProcessGps(speedLimit)).name("Validate raw simulated GPS").uid("gps-validation")
                .keyBy(GpsStreamingJob::taskKey)
                .process(new DetectDeviation()).name("Detect sustained plan deviation").uid("gps-sustained-deviation");
        processed.print("GPS_PROCESSED").name("Processing evidence log").uid("gps-evidence-log");
        String mongoUri = options.get("mongo-uri", System.getenv("LOGISTICS_MONGODB_URI"));
        if (mongoUri != null && !mongoUri.trim().isEmpty()) {
            processed.addSink(new MongoGpsSink(mongoUri, options.get("mongo-database", "logistics_system")))
                    .name("MongoDB GPS and anomaly upserts").uid("gps-mongodb");
        } else {
            System.out.println("MONGODB_SINK_DISABLED: console-only connectivity check; full chain is incomplete.");
        }
        return env;
    }

    public static class ProcessGps extends RichMapFunction<String, String> {
        private final double speedLimit;
        private transient GpsEventProcessor processor;

        public ProcessGps(double speedLimit) { this.speedLimit = speedLimit; }

        @Override
        public void open(Configuration configuration) { processor = new GpsEventProcessor(speedLimit); }

        @Override
        public String map(String value) throws Exception { return processor.process(value); }
    }
    static String taskKey(String value){try{return new ObjectMapper().readTree(value).path("taskId").asText("REJECTED");}catch(Exception e){return "REJECTED";}}
    public static class DetectDeviation extends KeyedProcessFunction<String,String,String> {
        private transient ValueState<VerifiedRoadDetector.State> state;
        private transient ObjectMapper json;
        @Override public void open(Configuration configuration){
            json=new ObjectMapper();ValueStateDescriptor<VerifiedRoadDetector.State> descriptor=new ValueStateDescriptor<>("deviation-state-v1",VerifiedRoadDetector.State.class);
            descriptor.enableTimeToLive(StateTtlConfig.newBuilder(org.apache.flink.api.common.time.Time.days(2)).build());state=getRuntimeContext().getState(descriptor);
        }
        @Override public void processElement(String value,Context context,Collector<String> out)throws Exception {
            ObjectNode result=(ObjectNode)json.readTree(value);VerifiedRoadDetector.State next=VerifiedRoadDetector.apply(result,state.value());if(next!=null)state.update(next);out.collect(json.writeValueAsString(result));
        }
    }
}

