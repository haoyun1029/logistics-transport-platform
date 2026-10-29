package cn.logistics.platform.api;
import cn.logistics.platform.service.UserManagementService;
import cn.logistics.platform.service.AuthService;
import cn.logistics.platform.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/business/users")
@RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN,UserRole.DISPATCHER})
public class UserManagementController {
    private final UserManagementService users;
    private final AuthService auth;
    public UserManagementController(UserManagementService users, AuthService auth){this.users=users;this.auth=auth;}
    @GetMapping public List<UserManagementService.UserView> list(HttpServletRequest r){return users.users(BusinessController.actor(r));}
    @PostMapping @RequireRoles(UserRole.SUPER_ADMIN)
    public Map<String,Long> create(@RequestBody UserManagementService.StaffInput request,HttpServletRequest r){return Map.of("id",users.createStaff(request,BusinessController.actor(r)));}
    @PostMapping("/{id}/contact")
    public void contact(@PathVariable long id,@RequestBody UserManagementService.ContactInput request,HttpServletRequest r){users.contact(id,request,BusinessController.actor(r));}
    @PostMapping("/{id}/role") @RequireRoles(UserRole.SUPER_ADMIN)
    public void role(@PathVariable long id,@RequestBody UserManagementService.RoleInput request,HttpServletRequest r){users.role(id,request,BusinessController.actor(r));}
    @PostMapping("/{id}/status") @RequireRoles(UserRole.SUPER_ADMIN)
    public void status(@PathVariable long id,@RequestBody UserManagementService.StatusInput request,HttpServletRequest r){users.status(id,request,BusinessController.actor(r));}
    @PostMapping("/{id}/reset-password") @RequireRoles(UserRole.SUPER_ADMIN)
    public Map<String,String> resetPassword(@PathVariable long id, HttpServletRequest r, jakarta.servlet.http.HttpServletResponse response){
        response.setHeader("Cache-Control", "no-store");
        return Map.of("temporaryPassword", auth.resetDriverPassword(id,BusinessController.actor(r)));
    }
}
