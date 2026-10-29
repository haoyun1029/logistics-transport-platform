package cn.logistics.platform.service;

import cn.logistics.platform.domain.GpsPointEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GpsSimulationServiceTest {

    @Test
    void publishesDeterministicRawPointsWithSimulationMarker() {
        DemoDataService demoData = new DemoDataService();
        demoData.accept("DEMO-2026-002");
        KafkaTemplate<String, GpsPointEvent> template = mock(KafkaTemplate.class);
        when(template.send(eq("logistics.gps.raw"), eq("DEMO-2026-002"), any(GpsPointEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        GpsSimulationService service = new GpsSimulationService(demoData, template, "logistics.gps.raw", true);
        var result = service.start("DEMO-2026-002");

        assertEquals(16, result.publishedCount());
        assertEquals("SIMULATED", result.sourceType());
        assertTrue(result.demoMode());
        assertTrue(result.message().contains("DEMO_MODE"));
        assertTrue(result.firstTimestamp().isBefore(result.lastTimestamp()));
        var events=ArgumentCaptor.forClass(GpsPointEvent.class);
        verify(template, times(16)).send(eq("logistics.gps.raw"), eq("DEMO-2026-002"), events.capture());
        assertTrue(events.getAllValues().stream().allMatch(GpsPointEvent::demoMode));
    }

    @Test
    void refusesPendingInMemoryFixtureWhenDemoModeIsOff() {
        DemoDataService demoData = new DemoDataService();
        demoData.accept("DEMO-2026-002");
        KafkaTemplate<String, GpsPointEvent> template = mock(KafkaTemplate.class);
        GpsSimulationService service = new GpsSimulationService(demoData, template, "logistics.gps.raw", false);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> service.start("DEMO-2026-002"));
        verify(template, org.mockito.Mockito.never()).send(any(), any(), any());
    }
}
