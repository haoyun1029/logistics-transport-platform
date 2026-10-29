package cn.logistics.platform.api;

import cn.logistics.platform.security.ApiAuthorizationInterceptor;
import cn.logistics.platform.security.UserPrincipal;
import cn.logistics.platform.security.UserRole;
import cn.logistics.platform.security.RequireRoles;
import cn.logistics.platform.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/bootstrap")
    public void bootstrap(@RequestBody BootstrapRequest request) {
        authService.bootstrapSuperAdmin(request.setupToken(), request.username(), request.password(), request.displayName());
    }

    @PostMapping("/register")
    public RegistrationResponse register(@RequestBody RegistrationRequest request) {
        authService.registerDriver(request.username(), request.password(), request.displayName(), request.phone());
        return new RegistrationResponse("PENDING", "注册已提交，管理员审核通过后即可登录。");
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        AuthService.LoginSession session = authService.login(request.username(), request.password());
        return new LoginResponse(session.token(), session.expiresAt(), AccountView.from(session.user()));
    }

    @PostMapping("/logout")
    @RequireRoles({UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.DISPATCHER, UserRole.DRIVER})
    public void logout(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            authService.logout(authorization.substring(7).trim());
        }
    }

    @GetMapping("/me")
    @RequireRoles({UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.DISPATCHER, UserRole.DRIVER})
    public AccountView me(HttpServletRequest request) {
        UserPrincipal principal = (UserPrincipal) request.getAttribute(ApiAuthorizationInterceptor.PRINCIPAL_ATTRIBUTE);
        return AccountView.from(principal);
    }

    @PostMapping("/change-password")
    @RequireRoles({UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.DISPATCHER, UserRole.DRIVER})
    public void changePassword(@RequestBody ChangePasswordRequest request, HttpServletRequest httpRequest) {
        UserPrincipal actor = (UserPrincipal) httpRequest.getAttribute(ApiAuthorizationInterceptor.PRINCIPAL_ATTRIBUTE);
        authService.changePassword(actor, request.currentPassword(), request.newPassword());
    }

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    public record BootstrapRequest(String setupToken, String username, String password, String displayName) {}
    public record RegistrationRequest(String username, String password, String displayName, String phone) {}
    public record LoginRequest(String username, String password) {}
    public record RegistrationResponse(String status, String message) {}
    public record LoginResponse(String token, Instant expiresAt, AccountView user) {}
    public record AccountView(long id, String username, String displayName, String role, boolean passwordChangeRequired) {
        static AccountView from(UserPrincipal principal) {
            return new AccountView(principal.id(), principal.username(), principal.displayName(), principal.role().name(), principal.passwordChangeRequired());
        }
    }
}
