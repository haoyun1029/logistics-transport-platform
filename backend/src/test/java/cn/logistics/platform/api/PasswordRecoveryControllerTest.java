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

@WebMvcTest({AuthController.class,UserManagementController.class,BusinessController.class,AdminController.class})
class PasswordRecoveryControllerTest {
    @MockitoBean AuthService auth;
    @MockitoBean UserManagementService users;
    @MockitoBean TransportBusinessService business;
    @MockitoBean MongoTelemetryService telemetry;
    @Autowired MockMvc mvc;
    UserPrincipal signIn(UserRole role,boolean temporary) {
        var user=new UserPrincipal(7,"tester","Tester",role,temporary);
        when(auth.findPrincipal("password-test")).thenReturn(Optional.of(user));
        return user;
    }
    @Test void passwordResetIsSuperAdministratorOnly() throws Exception {
        mvc.perform(post("/api/business/users/23/reset-password")).andExpect(status().isUnauthorized());
        for(var role:new UserRole[]{UserRole.ADMIN,UserRole.DISPATCHER,UserRole.DRIVER}) {
            signIn(role,false);
            mvc.perform(post("/api/business/users/23/reset-password").header("Authorization","Bearer password-test"))
                    .andExpect(status().isForbidden());
        }
        verify(auth,never()).resetDriverPassword(anyLong(),any());
        var root=signIn(UserRole.SUPER_ADMIN,false);
        when(auth.resetDriverPassword(23,root)).thenReturn("random-temporary-password");
        mvc.perform(post("/api/business/users/23/reset-password").header("Authorization","Bearer password-test"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.temporaryPassword").value("random-temporary-password"));
        verify(auth).resetDriverPassword(23,root);
    }
    @Test void temporarySessionCanOnlyReadIdentityChangePasswordOrLogout() throws Exception {
        var driver=signIn(UserRole.DRIVER,true);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer password-test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passwordChangeRequired").value(true));
        for(var path:new String[]{"/api/business/profile","/api/business/tasks","/api/business/vehicles"}) {
            mvc.perform(get(path).header("Authorization","Bearer password-test"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        }
        mvc.perform(post("/api/business/tasks/23/accept").header("Authorization","Bearer password-test"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(post("/api/auth/change-password").header("Authorization","Bearer password-test")
                        .contentType("application/json").content("{\"currentPassword\":\"temporary-password\",\"newPassword\":\"private-new-password\",\"userId\":999}"))
                .andExpect(status().isOk());
        verify(auth).changePassword(driver,"temporary-password","private-new-password");
        mvc.perform(post("/api/auth/logout").header("Authorization","Bearer password-test")).andExpect(status().isOk());
        verify(auth).logout("password-test");verifyNoInteractions(business,users,telemetry);
    }
    @Test void cannotBypassRestrictionByChoosingAnotherRoleOrBusinessPath() throws Exception {
        signIn(UserRole.SUPER_ADMIN,true);
        mvc.perform(get("/api/admin/drivers/pending").header("Authorization","Bearer password-test"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(post("/api/business/users/23/reset-password").header("Authorization","Bearer password-test"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        verify(auth,never()).resetDriverPassword(anyLong(),any());
    }
    @Test void allRolesCanChangeTheirOwnPasswordButAnonymousCannot() throws Exception {
        mvc.perform(post("/api/auth/change-password").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        for(var role:UserRole.values()) {
            var user=signIn(role,false);
            mvc.perform(post("/api/auth/change-password").header("Authorization","Bearer password-test")
                            .contentType("application/json").content("{\"currentPassword\":\"current-password\",\"newPassword\":\"new-password-1028\"}"))
                    .andExpect(status().isOk());
            verify(auth).changePassword(user,"current-password","new-password-1028");
        }
    }
}
