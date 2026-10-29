package cn.logistics.platform.api;

import cn.logistics.platform.service.AuthService;
import cn.logistics.platform.service.DemoDataService;
import cn.logistics.platform.service.MongoTelemetryService;
import cn.logistics.platform.security.UserPrincipal;
import cn.logistics.platform.security.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TelemetryController.class)
class TrajectoryControllerTest {
    @MockitoBean AuthService authService;
    @MockitoBean MongoTelemetryService telemetry;
    @MockitoBean DemoDataService demo;
    @Autowired MockMvc mvc;

    @Test void refusesUnauthenticatedRequestsBeforeReadingMongo() throws Exception {
        mvc.perform(get("/api/telemetry/demo-tasks/DEMO-2026-002/trajectory")).andExpect(status().isUnauthorized());
        verifyNoInteractions(telemetry);
    }

    @Test void permitsAllFourRolesToReadOnlySharedFixedDemo() throws Exception {
        when(demo.tasks()).thenReturn(new DemoDataService().tasks());
        when(telemetry.trajectory("DEMO-2026-002")).thenReturn(new MongoTelemetryService.Trajectory(
                "DEMO-2026-002", "CONNECTED", "FLINK_MONGODB", Instant.now(), false, List.of(), "No samples"));
        for (UserRole role : UserRole.values()) {
            when(authService.findPrincipal("test")).thenReturn(Optional.of(new UserPrincipal(1, "fixture", "Fixture", role)));
            mvc.perform(get("/api/telemetry/demo-tasks/DEMO-2026-002/trajectory").header("Authorization", "Bearer test"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("FLINK_MONGODB"))
                    .andExpect(jsonPath("$.points").isEmpty());
        }
        mvc.perform(get("/api/telemetry/demo-tasks/PRIVATE-USER-TASK/trajectory").header("Authorization", "Bearer test"))
                .andExpect(status().isBadRequest());
        verify(telemetry, never()).trajectory("PRIVATE-USER-TASK");
    }
}
