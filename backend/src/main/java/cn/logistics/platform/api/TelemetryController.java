package cn.logistics.platform.api;

import cn.logistics.platform.service.MongoTelemetryService;
import cn.logistics.platform.service.DemoDataService;
import cn.logistics.platform.security.RequireRoles;
import cn.logistics.platform.security.UserRole;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final MongoTelemetryService telemetry;
    private final DemoDataService demo;

    public TelemetryController(MongoTelemetryService telemetry, DemoDataService demo) {
        this.telemetry = telemetry;
        this.demo = demo;
    }

    @GetMapping("/gps")
    public MongoTelemetryService.TelemetrySummary gps(@RequestParam(required = false) String taskId) {
        return telemetry.summary(taskId);
    }

    @GetMapping("/demo-tasks/{taskId}/trajectory")
    @RequireRoles({UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.DISPATCHER, UserRole.DRIVER})
    public MongoTelemetryService.Trajectory trajectory(@PathVariable String taskId) {
        // This endpoint is limited to the shared fixed demo, never arbitrary production drivers' traces.
        if (demo.tasks().stream().noneMatch(task -> task.code().equals(taskId))) {
            throw new IllegalArgumentException("演示任务不存在。");
        }
        return telemetry.trajectory(taskId);
    }
}
