package cn.logistics.platform.service;

import cn.logistics.platform.repository.AccountRepository;
import cn.logistics.platform.security.AccountRecord;
import cn.logistics.platform.security.AuthenticationException;
import cn.logistics.platform.security.PasswordHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DriverReviewServiceTest {
    final AccountRepository accounts = mock(AccountRepository.class);
    final AuthService auth = new AuthService(accounts, "");

    @Test void pendingAndRejectedDriversCannotCreateSessions() {
        String hash = PasswordHasher.hash("test-only-password");
        for (String status : new String[]{"PENDING", "REJECTED"}) {
            when(accounts.lockByUsername("fixture")).thenReturn(Optional.of(new AccountRecord(2, "fixture", hash, "DRIVER", status, "Fixture", null)));
            assertThrows(AuthenticationException.class, () -> auth.login("fixture", "test-only-password"));
        }
        verify(accounts, never()).insertSession(anyString(), anyLong(), any());
    }

    @Test void rejectsInvalidNotesBeforeChangingAccount() {
        assertThrows(IllegalArgumentException.class, () -> auth.reviewDriver(2, 1, false, "  "));
        assertThrows(IllegalArgumentException.class, () -> auth.reviewDriver(2, 1, true, "a".repeat(301)));
        verifyNoInteractions(accounts);
    }

    @Test void duplicateReviewDoesNotCreateAnotherAudit() {
        when(accounts.updateDriverReview(2, "APPROVED", 1, null)).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> auth.reviewDriver(2, 1, true, null));
        verify(accounts, never()).insertAudit(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void auditNotesRemainValidJsonWithControlCharacters() throws Exception {
        String note = "资料\t待补充\n\"证件\"\\路径";
        when(accounts.updateDriverReview(2, "REJECTED", 1, note)).thenReturn(1);
        auth.reviewDriver(2, 1, false, note);
        var json = ArgumentCaptor.forClass(String.class);
        verify(accounts).insertAudit(eq(1L), eq("DRIVER_REJECTED"), eq("USER"), eq("2"), json.capture());
        assertEquals(note, new ObjectMapper().readTree(json.getValue()).get("note").asText());
    }
}
