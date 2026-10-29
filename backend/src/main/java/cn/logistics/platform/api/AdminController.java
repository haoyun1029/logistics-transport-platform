package cn.logistics.platform.api;

import cn.logistics.platform.repository.AccountRepository;
import cn.logistics.platform.security.ApiAuthorizationInterceptor;
import cn.logistics.platform.security.RequireRoles;
import cn.logistics.platform.security.UserPrincipal;
import cn.logistics.platform.security.UserRole;
import cn.logistics.platform.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequireRoles({UserRole.SUPER_ADMIN, UserRole.ADMIN})
public class AdminController {

    private final AuthService authService;

    public AdminController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/drivers/pending")
    public List<PendingDriverResponse> pendingDrivers() {
        return authService.pendingDrivers().stream().map(PendingDriverResponse::from).toList();
    }

    @PostMapping("/drivers/{driverId}/review")
    public void reviewDriver(@PathVariable long driverId,
                             @RequestBody DriverReviewRequest request,
                             HttpServletRequest httpRequest) {
        if (request.approve() == null) throw new IllegalArgumentException("请选择通过或拒绝。");
        UserPrincipal reviewer = principal(httpRequest);
        authService.reviewDriver(driverId, reviewer.id(), request.approve(), request.note());
    }

    @GetMapping("/drivers/reviews")
    public List<AccountRepository.DriverReview> driverReviews() {
        return authService.driverReviews();
    }

    @DeleteMapping("/users/{userId}")
    @RequireRoles({UserRole.SUPER_ADMIN})
    public void deleteUser(@PathVariable long userId, HttpServletRequest httpRequest) {
        UserPrincipal actor = principal(httpRequest);
        authService.deleteUser(userId, actor.id());
    }

    private UserPrincipal principal(HttpServletRequest request) {
        return (UserPrincipal) request.getAttribute(ApiAuthorizationInterceptor.PRINCIPAL_ATTRIBUTE);
    }

    public record DriverReviewRequest(Boolean approve, String note) {}

    public record PendingDriverResponse(long id, String username, String displayName, String phone, Instant registeredAt) {
        static PendingDriverResponse from(AccountRepository.PendingDriver driver) {
            return new PendingDriverResponse(driver.id(), driver.username(), driver.displayName(),
                    driver.phone(), driver.createdAt());
        }
    }
}
