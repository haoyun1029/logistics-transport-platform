package cn.logistics.platform.api;
import cn.logistics.platform.security.*;
import cn.logistics.platform.service.RouteOptimizationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/business/optimizations")
@RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN})
public class RouteOptimizationController {
    private final RouteOptimizationService optimization;
    public RouteOptimizationController(RouteOptimizationService optimization){this.optimization=optimization;}
    @GetMapping public List<RouteOptimizationService.Evaluation> history(){return optimization.history();}
    @PostMapping public RouteOptimizationService.Evaluation evaluate(@RequestBody Request request,HttpServletRequest r){return optimization.evaluate(request.planRouteId(),BusinessController.actor(r));}
    public record Request(long planRouteId){}
}
