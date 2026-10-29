package cn.logistics.platform.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import cn.logistics.platform.service.AuthService;
import cn.logistics.platform.service.ComponentHealthService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private ComponentHealthService componentHealthService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportsApplicationHealth() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.application").value("logistics-platform"))
                .andExpect(jsonPath("$.checkedAt").isNotEmpty());
    }
}
