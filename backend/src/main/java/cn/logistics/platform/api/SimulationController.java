package cn.logistics.platform.api;

import cn.logistics.platform.security.RequireRoles;
import cn.logistics.platform.security.UserRole;
import cn.logistics.platform.service.GpsSimulationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/simulations")
public class SimulationController {

    private final GpsSimulationService simulationService;

    public SimulationController(GpsSimulationService simulationService) {
        this.simulationService = simulationService;
    }

    @PostMapping("/tasks/{taskCode}/start")
    @RequireRoles({UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.DISPATCHER, UserRole.DRIVER})
    public GpsSimulationService.SimulationResult start(@PathVariable String taskCode) {
        return simulationService.start(taskCode);
    }
}
