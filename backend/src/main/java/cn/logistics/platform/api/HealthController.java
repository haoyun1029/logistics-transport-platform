package cn.logistics.platform.api;

import cn.logistics.platform.service.ComponentHealthService;
import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final ComponentHealthService components;

    public HealthController(ComponentHealthService components) {
        this.components = components;
    }

    @GetMapping
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "application", "logistics-platform",
                "checkedAt", Instant.now().toString());
    }

    @GetMapping("/components")
    public Map<String, Object> componentHealth() {
        return Map.of("checkedAt", Instant.now().toString(), "components", components.checkAll());
    }
}
