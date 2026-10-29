package cn.logistics.platform.api;

import cn.logistics.platform.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
class BootstrapDatabaseFailureTest {
    @MockitoBean
    private AuthService authService;
    @Autowired
    private MockMvc mvc;

    private static final String REQUEST = """
            {"setupToken":"test-token","username":"admin","password":"test-password","displayName":"系统管理员"}
            """;

    @Test
    void acceptsUtf8DisplayName() throws Exception {
        mvc.perform(post("/api/auth/bootstrap").contentType("application/json; charset=utf-8").content(REQUEST))
                .andExpect(status().isOk());
        verify(authService).bootstrapSuperAdmin("test-token", "admin", "test-password", "系统管理员");
    }

    @Test
    void reportsPoolTimeoutWithoutLeakingConnectionDetails() throws Exception {
        failBootstrap(new CannotCreateTransactionException("transaction failed",
                new SQLTransientConnectionException("secret-host-and-password", "08001")));
        mvc.perform(post("/api/auth/bootstrap").contentType("application/json").content(REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("secret-host-and-password"))));
    }

    @Test
    void distinguishesDatabaseAccessDeniedFromPoolTimeout() throws Exception {
        failBootstrap(new CannotCreateTransactionException("transaction failed",
                new SQLTransientConnectionException("pool timeout", new SQLException("secret-account", "28000", 1045))));
        mvc.perform(post("/api/auth/bootstrap").contentType("application/json").content(REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DB_ACCESS_DENIED"))
                .andExpect(content().string(not(containsString("secret-account"))));
    }

    @Test
    void reportsMissingTableWithoutExposingSql() throws Exception {
        failBootstrap(new BadSqlGrammarException("bootstrap", "secret-query",
                new SQLException("secret-query", "42S02", 1146)));
        mvc.perform(post("/api/auth/bootstrap").contentType("application/json").content(REQUEST))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DB_TABLE_MISSING"))
                .andExpect(content().string(not(containsString("secret-query"))));
    }

    private void failBootstrap(RuntimeException failure) {
        doThrow(failure).when(authService).bootstrapSuperAdmin(anyString(), anyString(), anyString(), anyString());
    }
}
