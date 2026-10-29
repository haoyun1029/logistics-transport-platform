package cn.logistics.platform.api;
import cn.logistics.platform.security.*;
import cn.logistics.platform.service.RouteManagementService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
@RestController
@RequestMapping("/api/business/routes")
@RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN,UserRole.DISPATCHER,UserRole.DRIVER})
public class RouteManagementController {
    private final RouteManagementService routes;
    public RouteManagementController(RouteManagementService routes) {this.routes=routes;}
    @GetMapping public List<RouteManagementService.RouteView> routes(){return routes.routes();}
    @GetMapping("/{id}") public RouteManagementService.RouteView route(@PathVariable long id){return routes.route(id);}
    @PostMapping @RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN})
    public Map<String,Long> create(@RequestBody RouteManagementService.RouteInput request,HttpServletRequest r){return Map.of("id",routes.create(request,BusinessController.actor(r)));}
    @PostMapping("/{id}/segments") @RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN})
    public Map<String,Long> save(@PathVariable long id,@RequestBody RouteManagementService.SegmentInput request,HttpServletRequest r){return Map.of("id",routes.saveSegment(id,request,BusinessController.actor(r)));}
    @PostMapping("/{id}/segments/{segment}/verify") @RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN})
    public void verify(@PathVariable long id,@PathVariable long segment,@RequestBody RouteManagementService.Verification request,HttpServletRequest r){routes.verifySegment(id,segment,request,BusinessController.actor(r));}
    @PostMapping("/{id}/publish") @RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN})
    public void publish(@PathVariable long id,HttpServletRequest r){routes.publish(id,BusinessController.actor(r));}
    @PostMapping("/{id}/versions") @RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN})
    public Map<String,Long> copy(@PathVariable long id,HttpServletRequest r){return Map.of("id",routes.copyVersion(id,BusinessController.actor(r)));}
}
