package cn.logistics.platform.api;

import cn.logistics.platform.security.*;
import cn.logistics.platform.service.TransportBusinessService;
import cn.logistics.platform.service.MongoTelemetryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/business")
@RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN,UserRole.DISPATCHER,UserRole.DRIVER})
public class BusinessController {
    private final TransportBusinessService business;
    private final MongoTelemetryService telemetry;
    public BusinessController(TransportBusinessService business,MongoTelemetryService telemetry) { this.business=business;this.telemetry=telemetry; }
    public static UserPrincipal actor(HttpServletRequest request) { return (UserPrincipal)request.getAttribute(ApiAuthorizationInterceptor.PRINCIPAL_ATTRIBUTE); }
    @GetMapping("/tasks") public List<TransportBusinessService.TaskView> tasks(HttpServletRequest r) { return business.tasks(actor(r)); }
    @GetMapping("/tasks/{id}") public TransportBusinessService.TaskView task(@PathVariable long id,HttpServletRequest r) { return business.task(id,actor(r)); }
    @GetMapping("/tasks/{id}/trajectory") public MongoTelemetryService.Trajectory trajectory(@PathVariable long id,HttpServletRequest r) { return telemetry.trajectory(business.task(id,actor(r)).code()); }
    @PostMapping("/tasks") @RequireRoles(UserRole.DISPATCHER)
    public TransportBusinessService.TaskView create(@RequestBody CreateTask request,HttpServletRequest r) {
        return business.create(request.routeId(),request.cargoDescription(),request.cargoWeightTons(),request.pricePerKm(),request.deliveryDeadline(),request.details(),actor(r));
    }
    @PostMapping("/tasks/{id}/details") @RequireRoles(UserRole.DISPATCHER)
    public TransportBusinessService.TaskView updateDetails(@PathVariable long id,@RequestBody TransportBusinessService.TaskDetails request,HttpServletRequest r) {
        return business.updatePendingTaskDetails(id,request,actor(r));
    }
    @PostMapping("/tasks/{id}/historical-rate") @RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN,UserRole.DISPATCHER})
    public TransportBusinessService.TaskView backfillHistoricalRate(@PathVariable long id,@RequestBody TransportBusinessService.HistoricalRate request,HttpServletRequest r) {
        return business.addHistoricalRate(id,request,actor(r));
    }
    @PostMapping("/tasks/{id}/assign") @RequireRoles(UserRole.DISPATCHER)
    public TransportBusinessService.TaskView assign(@PathVariable long id,@RequestBody Assignment request,HttpServletRequest r) { return business.assign(id,request.driverId(),request.vehicleId(),actor(r)); }
    @PostMapping("/tasks/{id}/accept") @RequireRoles(UserRole.DRIVER)
    public TransportBusinessService.TaskView accept(@PathVariable long id,HttpServletRequest r) { return business.accept(id,actor(r)); }
    @PostMapping("/tasks/{id}/feedback") @RequireRoles(UserRole.DRIVER)
    public void feedback(@PathVariable long id,@RequestBody TransportBusinessService.TaskIssue request,HttpServletRequest r) {
        business.reportIssue(id,request,actor(r));
    }
    @PostMapping("/tasks/{id}/complete") @RequireRoles(UserRole.DRIVER)
    public TransportBusinessService.TaskView complete(@PathVariable long id,@RequestBody TransportBusinessService.Completion request,HttpServletRequest r) { return business.complete(id,request,actor(r)); }
    @PostMapping("/tasks/{id}/cancel") @RequireRoles(UserRole.DISPATCHER)
    public TransportBusinessService.TaskView cancel(@PathVariable long id,HttpServletRequest r) { return business.cancel(id,actor(r)); }
    @GetMapping("/vehicles") public List<TransportBusinessService.VehicleView> vehicles(HttpServletRequest r) { return business.vehicles(actor(r)); }
    @PostMapping("/vehicles") @RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN})
    public Map<String,Long> createVehicle(@RequestBody TransportBusinessService.VehicleInput request,HttpServletRequest r) { return Map.of("id",business.createVehicle(request,actor(r))); }
    @PostMapping("/vehicles/{id}/profile") @RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN})
    public void updateVehicle(@PathVariable long id,@RequestBody TransportBusinessService.VehicleInput request,HttpServletRequest r) { business.updateVehicle(id,request,actor(r)); }
    @PostMapping("/vehicles/{id}/status") @RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN})
    public void vehicleStatus(@PathVariable long id,@RequestBody VehicleStatus request,HttpServletRequest r) { business.vehicleStatus(id,request.status(),actor(r)); }
    @PostMapping("/vehicles/{id}/maintenance-reminder") @RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN})
    public void maintenanceReminder(@PathVariable long id,@RequestBody MaintenanceReminder request,HttpServletRequest r) { business.maintenanceReminder(id,request.nextServiceOn(),actor(r)); }
    @GetMapping("/drivers") @RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN,UserRole.DISPATCHER})
    public List<TransportBusinessService.DriverOption> drivers() { return business.drivers(); }
    @GetMapping("/route-options") public List<TransportBusinessService.RouteOption> routes() { return business.routes(); }
    @GetMapping("/maintenance") @RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN})
    public List<TransportBusinessService.Maintenance> maintenance(HttpServletRequest r) { return business.maintenance(actor(r)); }
    @PostMapping("/maintenance") @RequireRoles({UserRole.SUPER_ADMIN,UserRole.ADMIN})
    public Map<String,Long> maintenance(@RequestBody TransportBusinessService.MaintenanceInput request,HttpServletRequest r) { return Map.of("id",business.recordMaintenance(request,actor(r))); }
    @GetMapping("/profile") public TransportBusinessService.Profile profile(HttpServletRequest r) { return business.profile(actor(r)); }
    @PostMapping("/profile") public void profile(@RequestBody TransportBusinessService.ProfileInput request,HttpServletRequest r) { business.updateProfile(request,actor(r)); }
    public record CreateTask(long routeId,String cargoDescription,Double cargoWeightTons,Double pricePerKm,
                             java.time.LocalDateTime deliveryDeadline,String details) {}
    public record Assignment(long driverId,long vehicleId) {}
    public record TaskIssue(String type,String message,java.time.LocalDateTime requestedDeadline) {}
    public record VehicleStatus(String status) {}
    public record MaintenanceReminder(java.time.LocalDate nextServiceOn) {}
}
