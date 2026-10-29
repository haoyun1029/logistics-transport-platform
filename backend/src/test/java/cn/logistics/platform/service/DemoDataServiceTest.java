package cn.logistics.platform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DemoDataServiceTest {

    @Test
    void exposesThreeDifferentOdRoutesAndSameOdScores() {
        DemoDataService service = new DemoDataService();

        assertEquals(4, service.routes().size());
        assertEquals(0, service.overview().scores().size());
        assertEquals("PENDING_VERIFICATION", service.routes().getFirst().verificationStatus());
        assertEquals(0.40, service.overview().weights().travelTime());
    }

    @Test
    void taskTransitionsRetainImpossibleAcceleratedFeedbackAsInvalid() {
        DemoDataService service = new DemoDataService();

        assertEquals("PENDING_ACCEPTANCE", service.tasks().getLast().status());
        assertEquals("IN_TRANSIT", service.accept("DEMO-2026-002").status());
        var completed = service.complete("DEMO-2026-002", new DemoDataService.CompletionRequest(902, 280, true));
        assertEquals("COMPLETED", completed.status());
        assertEquals("INVALID", completed.sampleStatus());
        assertEquals(902, completed.actualDistanceKm());
    }

    @Test
    void rejectsIllegalTaskTransition() {
        DemoDataService service = new DemoDataService();
        assertThrows(IllegalStateException.class, () -> service.complete("DEMO-2026-002",
                new DemoDataService.CompletionRequest(900, 280, true)));
    }
}
