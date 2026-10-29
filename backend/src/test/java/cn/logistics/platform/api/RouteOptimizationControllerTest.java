package cn.logistics.platform.api;

import cn.logistics.platform.security.UserPrincipal;
import cn.logistics.platform.security.UserRole;
import cn.logistics.platform.service.AuthService;
import cn.logistics.platform.service.RouteOptimizationService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RouteOptimizationController.class)
class RouteOptimizationControllerTest {
    @MockitoBean AuthService auth;
    @MockitoBean RouteOptimizationService optimization;
    @Autowired MockMvc mvc;

    @Test void administratorCanReadAndRunRouteOptimization() throws Exception {
        var admin = new UserPrincipal(12, "admin", "管理员", UserRole.ADMIN);
        when(auth.findPrincipal("optimization-test")).thenReturn(Optional.of(admin));
        when(optimization.history()).thenReturn(List.of());
        when(optimization.evaluate(eq(3L), eq(admin))).thenReturn(
                new RouteOptimizationService.Evaluation(1, Instant.parse("2026-10-26T00:00:00Z"), JsonNodeFactory.instance.objectNode()));

        mvc.perform(get("/api/business/optimizations").header("Authorization", "Bearer optimization-test"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(post("/api/business/optimizations").header("Authorization", "Bearer optimization-test")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planRouteId\":3}"))
                .andExpect(status().isOk());

        verify(optimization).evaluate(3, admin);
    }

    @Test void dispatcherAndDriverCannotReadOrRunRouteOptimization() throws Exception {
        var dispatcher = new UserPrincipal(12, "dispatch", "调度员", UserRole.DISPATCHER);
        when(auth.findPrincipal("optimization-test")).thenReturn(Optional.of(dispatcher));

        mvc.perform(get("/api/business/optimizations").header("Authorization", "Bearer optimization-test"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/business/optimizations").header("Authorization", "Bearer optimization-test")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planRouteId\":3}"))
                .andExpect(status().isForbidden());

        var driver = new UserPrincipal(15, "driver", "司机", UserRole.DRIVER);
        when(auth.findPrincipal("optimization-test")).thenReturn(Optional.of(driver));

        mvc.perform(get("/api/business/optimizations").header("Authorization", "Bearer optimization-test"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/business/optimizations").header("Authorization", "Bearer optimization-test")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planRouteId\":3}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(optimization);
    }
}
