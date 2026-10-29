package cn.logistics.platform.api;

import cn.logistics.platform.service.DemoDataService;
import cn.logistics.platform.security.RequireRoles;
import cn.logistics.platform.security.UserRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private final DemoDataService demoData;

    public DemoController(DemoDataService demoData) {
        this.demoData = demoData;
    }

    @GetMapping("/overview")
    public DemoDataService.Overview overview() {
        return demoData.overview();
    }

    @GetMapping("/routes")
    public List<DemoDataService.RouteView> routes() {
        return demoData.routes();
    }

    @GetMapping("/tasks")
    public List<DemoDataService.TaskView> tasks() {
        return demoData.tasks();
    }

    @PostMapping("/tasks/{taskCode}/accept")
    @RequireRoles({UserRole.DRIVER})
    public DemoDataService.TaskView accept(@PathVariable String taskCode) {
        return demoData.accept(taskCode);
    }

    @PostMapping("/tasks/{taskCode}/complete")
    @RequireRoles({UserRole.DRIVER})
    public DemoDataService.TaskView complete(@PathVariable String taskCode,
                                             @RequestBody DemoDataService.CompletionRequest request) {
        return demoData.complete(taskCode, request);
    }

    @PostMapping("/tasks/{taskCode}/cancel")
    @RequireRoles({UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.DISPATCHER})
    public DemoDataService.TaskView cancel(@PathVariable String taskCode) {
        return demoData.cancel(taskCode);
    }
}
