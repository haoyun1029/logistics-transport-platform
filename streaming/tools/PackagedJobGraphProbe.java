import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/** Run after package with the Maven test classpath (which also contains unshaded Kafka classes). */
public class PackagedJobGraphProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected packaged JAR path");
        URL jarUrl = new File(args[0]).toURI().toURL();
        try (URLClassLoader jobLoader = new URLClassLoader(new URL[]{jarUrl}, PackagedJobGraphProbe.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.startsWith("cn.logistics.streaming.")) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) loaded = findClass(name);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                    return super.loadClass(name, resolve);
                }
            }
        }) {
            Class<?> initializer = jobLoader.loadClass("cn.logistics.streaming.shaded.flink.kafka.source.enumerator.initializer.OffsetsInitializer");
            Class<?> strategy = jobLoader.loadClass("cn.logistics.streaming.shaded.kafka.clients.consumer.OffsetResetStrategy");
            if (initializer.getClassLoader() != jobLoader || strategy.getClassLoader() != jobLoader) {
                throw new IllegalStateException("Connector and client must both come from the packaged job");
            }
            initializer.getMethod("committedOffsets", strategy);
            // Confirm a conflicting unshaded parent connector is present in this test environment.
            Class.forName("org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer");
            Class<?> job = jobLoader.loadClass("cn.logistics.streaming.GpsStreamingJob");
            Method configure = job.getDeclaredMethod("createEnvironment", String[].class);
            configure.setAccessible(true);
            StreamExecutionEnvironment env = (StreamExecutionEnvironment) configure.invoke(null, (Object) new String[0]);
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try {
                Thread.currentThread().setContextClassLoader(jobLoader);
                env.getStreamGraph().getJobGraph();
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
            System.out.println("PACKAGED_JOB_GRAPH_OK connectorAndClientIsolated=true parentConnectorPresent=true");
        }
    }
}
