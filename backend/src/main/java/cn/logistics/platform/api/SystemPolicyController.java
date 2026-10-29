package cn.logistics.platform.api;
import cn.logistics.platform.security.*;
import cn.logistics.platform.service.SystemPolicyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/business/policy")
@RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN})
public class SystemPolicyController {
    private final SystemPolicyService policy;
    public SystemPolicyController(SystemPolicyService policy){this.policy=policy;}
    @GetMapping public SystemPolicyService.Policy current(){return policy.current();}
    @PostMapping public SystemPolicyService.Policy save(@RequestBody SystemPolicyService.Policy request,HttpServletRequest r){return policy.save(request,BusinessController.actor(r));}
}
