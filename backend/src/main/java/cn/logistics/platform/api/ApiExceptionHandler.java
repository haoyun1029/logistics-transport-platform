package cn.logistics.platform.api;

import cn.logistics.platform.security.AuthenticationException;
import cn.logistics.platform.security.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.sql.SQLException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({CannotCreateTransactionException.class, CannotGetJdbcConnectionException.class,
            BadSqlGrammarException.class})
    public ResponseEntity<Map<String, String>> databaseFailure(RuntimeException exception) {
        String code = "DB_UNAVAILABLE";
        String message = "数据库连接暂不可用，请检查虚拟机、MySQL 服务和后端连接配置，稍后重试。";
        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        if (exception instanceof BadSqlGrammarException) {
            code = "DB_SCHEMA_ERROR";
            message = "数据库表结构或 SQL 不兼容，请核对 schema.sql 和后端版本。";
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                // Log identifiers only: JDBC messages can contain connection details.
                LOG.warn("Database failure: type={}, SQLState={}, vendorCode={}",
                        exception.getClass().getSimpleName(), sql.getSQLState(), sql.getErrorCode());
                switch (sql.getErrorCode()) {
                    case 1044, 1045 -> {
                        code = "DB_ACCESS_DENIED";
                        message = "数据库账户认证或授权失败，请核对 .env 的数据库配置，修改后重启后端。";
                    }
                    case 1049 -> {
                        code = "DB_NOT_FOUND";
                        message = "配置的数据库不存在，请先创建数据库并导入 schema.sql。";
                    }
                    case 1146 -> {
                        code = "DB_TABLE_MISSING";
                        message = "缺少业务数据表，请将当前 backend/db/schema.sql 导入配置的数据库。";
                    }
                    default -> { }
                }
            }
        }
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> unauthenticated(AuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("code", "UNAUTHENTICATED", "message", exception.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(ForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code", "FORBIDDEN", "message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_REQUEST", "message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "STATE_CONFLICT", "message", exception.getMessage()));
    }
}
