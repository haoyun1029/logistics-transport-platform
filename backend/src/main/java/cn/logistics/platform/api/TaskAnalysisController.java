package cn.logistics.platform.api;
import cn.logistics.platform.security.*;
import cn.logistics.platform.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/business/tasks/{id}")
@RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN,UserRole.DRIVER})
public class TaskAnalysisController {
    private final TaskMatchingService matching;private final BusinessGpsService gps;
    public TaskAnalysisController(TaskMatchingService matching,BusinessGpsService gps){this.matching=matching;this.gps=gps;}
    @GetMapping("/analysis") public JsonNode latest(@PathVariable long id,HttpServletRequest r){return matching.latest(id,BusinessController.actor(r));}
    @PostMapping("/match") public JsonNode match(@PathVariable long id,HttpServletRequest r){return matching.analyse(id,BusinessController.actor(r));}
    @PostMapping("/simulate") @RequireRoles(UserRole.DRIVER)
    public BusinessGpsService.Result simulate(@PathVariable long id,HttpServletRequest r){
        // Drivers execute the route assigned to the task; route alternatives belong to analysis workflows.
        return gps.start(id,null,BusinessController.actor(r));
    }
}
