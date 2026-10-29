package cn.logistics.platform.api;

import cn.logistics.platform.security.UserPrincipal;
import cn.logistics.platform.security.UserRole;
import cn.logistics.platform.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
class DriverReviewControllerTest {
    @MockitoBean AuthService auth;
    @Autowired MockMvc mvc;

    void signIn(UserRole role) {
        when(auth.findPrincipal("review-test")).thenReturn(Optional.of(new UserPrincipal(7, "reviewer", "Reviewer", role)));
    }

    @Test void blocksAnonymousAndNonAdministratorsFromReadingAndReviewing() throws Exception {
        mvc.perform(get("/api/admin/drivers/pending")).andExpect(status().isUnauthorized());
        for (UserRole role : List.of(UserRole.DRIVER, UserRole.DISPATCHER)) {
            signIn(role);
            mvc.perform(get("/api/admin/drivers/reviews").header("Authorization", "Bearer review-test")).andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/drivers/pending").header("Authorization", "Bearer review-test")).andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/drivers/23/review").header("Authorization", "Bearer review-test")
                    .contentType("application/json").content("{\"approve\":true}"))
                    .andExpect(status().isForbidden());
        }
        verify(auth, never()).pendingDrivers(); verify(auth, never()).driverReviews();
        verify(auth, never()).reviewDriver(anyLong(), anyLong(), anyBoolean(), any());
    }

    @Test void allowsBothAdministratorRolesAndTakesReviewerFromSession() throws Exception {
        for (UserRole role : List.of(UserRole.ADMIN, UserRole.SUPER_ADMIN)) {
            signIn(role);
            mvc.perform(get("/api/admin/drivers/reviews").header("Authorization", "Bearer review-test")).andExpect(status().isOk());
            mvc.perform(post("/api/admin/drivers/23/review").header("Authorization", "Bearer review-test")
                    .contentType("application/json").content("{\"approve\":false,\"note\":\"资料待补充\",\"reviewerId\":999}"))
                    .andExpect(status().isOk());
        }
        verify(auth, times(2)).reviewDriver(23, 7, false, "资料待补充");
    }

    @Test void missingDecisionCannotAccidentallyRejectAnApplication() throws Exception {
        signIn(UserRole.ADMIN);
        mvc.perform(post("/api/admin/drivers/23/review").header("Authorization", "Bearer review-test")
                .contentType("application/json").content("{\"note\":\"No decision\"}"))
                .andExpect(status().isBadRequest());
        verify(auth, never()).reviewDriver(anyLong(), anyLong(), anyBoolean(), any());
    }
}
