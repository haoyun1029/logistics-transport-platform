package cn.logistics.platform.api;

import cn.logistics.platform.security.*;
import cn.logistics.platform.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BusinessController.class)
class BusinessControllerTest {
    @MockitoBean AuthService auth;
    @MockitoBean TransportBusinessService business;
    @MockitoBean MongoTelemetryService telemetry;
    @Autowired MockMvc mvc;
    UserPrincipal login(UserRole role) {
        var principal = new UserPrincipal(7,"tester","Tester",role);
        when(auth.findPrincipal("business-test")).thenReturn(Optional.of(principal));
        return principal;
    }
    @Test void taskExecutionTakesIdentityFromSession() throws Exception {
        var driver=login(UserRole.DRIVER);
        mvc.perform(post("/api/business/tasks/23/accept").header("Authorization","Bearer business-test")
                .contentType("application/json").content("{\"driverId\":999}"))
                .andExpect(status().isOk());
        verify(business).accept(23,driver);
    }
    @Test void taskIssueFeedbackUsesAuthenticatedDriverIdentity() throws Exception {
        var driver=login(UserRole.DRIVER);
        mvc.perform(post("/api/business/tasks/23/feedback").header("Authorization","Bearer business-test")
                        .contentType("application/json")
                        .content("{\"type\":\"TIME_EXTENSION\",\"message\":\"计划时间太短\",\"requestedDeadline\":\"2026-10-28T18:00:00\"}"))
                .andExpect(status().isOk());
        verify(business).reportIssue(eq(23L),any(TransportBusinessService.TaskIssue.class),eq(driver));
    }
    @Test void checksOwnershipBeforeQueryingMongo() throws Exception {
        var driver=login(UserRole.DRIVER);
        when(business.task(23,driver)).thenThrow(new ForbiddenException("Not your task"));
        mvc.perform(get("/api/business/tasks/23/trajectory").header("Authorization","Bearer business-test"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(telemetry);
    }
    @Test void creationAndAssignmentAreDispatcherOnly() throws Exception {
        for(var role: new UserRole[]{UserRole.DRIVER,UserRole.ADMIN,UserRole.SUPER_ADMIN}) {
            login(role);
            mvc.perform(post("/api/business/tasks").header("Authorization","Bearer business-test")
                    .contentType("application/json").content("{\"routeId\":1}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/business/tasks/23/assign").header("Authorization","Bearer business-test")
                    .contentType("application/json").content("{\"driverId\":7,\"vehicleId\":2}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(business);
    }
    @Test void dispatcherCreatesTaskWithPerKilometerRate() throws Exception {
        var dispatcher = login(UserRole.DISPATCHER);
        mvc.perform(post("/api/business/tasks").header("Authorization", "Bearer business-test")
                        .contentType("application/json")
                        .content("{\"routeId\":1,\"cargoDescription\":\"冷链水果\",\"cargoWeightTons\":8.5,\"pricePerKm\":6.25,\"deliveryDeadline\":\"2026-10-30T18:00:00\",\"details\":\"装卸注意事项\"}"))
                .andExpect(status().isOk());
        verify(business).create(eq(1L), eq("冷链水果"), eq(8.5), eq(6.25),
                eq(java.time.LocalDateTime.of(2026,10,30,18,0)), eq("装卸注意事项"), eq(dispatcher));
    }
    @Test void historicalRateBackfillIsAvailableOnlyToBusinessStaff() throws Exception {
        var dispatcher=login(UserRole.DISPATCHER);
        mvc.perform(post("/api/business/tasks/23/historical-rate").header("Authorization","Bearer business-test")
                        .contentType("application/json").content("{\"pricePerKm\":5.25,\"sourceReference\":\"结算凭据 2026-023\"}"))
                .andExpect(status().isOk());
        verify(business).addHistoricalRate(eq(23L),any(TransportBusinessService.HistoricalRate.class),eq(dispatcher));
        login(UserRole.DRIVER);
        mvc.perform(post("/api/business/tasks/23/historical-rate").header("Authorization","Bearer business-test")
                        .contentType("application/json").content("{\"pricePerKm\":5.25,\"sourceReference\":\"结算凭据 2026-023\"}"))
                .andExpect(status().isForbidden());
    }
    @Test void anonymousCannotReadTasksOrProfiles() throws Exception {
        mvc.perform(get("/api/business/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/business/profile")).andExpect(status().isUnauthorized());
    }
    @Test void driversCanReadVehicleReminderButCannotReadOrWriteMaintenanceRecords() throws Exception {
        var driver=login(UserRole.DRIVER);
        when(business.vehicles(driver)).thenReturn(java.util.List.of());
        mvc.perform(get("/api/business/vehicles").header("Authorization","Bearer business-test"))
                .andExpect(status().isOk());
        verify(business).vehicles(driver);
        mvc.perform(get("/api/business/maintenance").header("Authorization","Bearer business-test"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/business/maintenance").header("Authorization","Bearer business-test")
                        .contentType("application/json")
                        .content("{\"vehicleId\":1,\"description\":\"service\",\"servicedOn\":\"2026-10-20\",\"nextServiceOn\":\"2027-01-20\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/business/vehicles/1/maintenance-reminder").header("Authorization","Bearer business-test")
                        .contentType("application/json").content("{\"nextServiceOn\":\"2027-01-20\"}"))
                .andExpect(status().isForbidden());
        verifyNoMoreInteractions(business);
    }
    @Test void adminCanSetDriverMaintenanceReminder() throws Exception {
        var admin=login(UserRole.ADMIN);
        mvc.perform(post("/api/business/vehicles/1/maintenance-reminder").header("Authorization","Bearer business-test")
                        .contentType("application/json").content("{\"nextServiceOn\":\"2027-01-20\"}"))
                .andExpect(status().isOk());
        verify(business).maintenanceReminder(1,java.time.LocalDate.of(2027,1,20),admin);
    }
}
