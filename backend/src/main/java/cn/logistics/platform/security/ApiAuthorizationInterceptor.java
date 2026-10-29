package cn.logistics.platform.security;

import cn.logistics.platform.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

@Component
public class ApiAuthorizationInterceptor implements HandlerInterceptor {

    public static final String PRINCIPAL_ATTRIBUTE = "authenticatedUser";

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public ApiAuthorizationInterceptor(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method)) return true;
        RequireRoles required = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequireRoles.class);
        if (required == null) required = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequireRoles.class);
        if (required == null) return true;

        String token = extractBearerToken(request.getHeader("Authorization"));
        Optional<UserPrincipal> principal = authService.findPrincipal(token);
        if (principal.isEmpty()) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "请先登录。");
            return false;
        }
        UserPrincipal user = principal.get();
        if (user.passwordChangeRequired() && !java.util.Set.of(
                "/api/auth/me", "/api/auth/logout", "/api/auth/change-password").contains(request.getRequestURI())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "PASSWORD_CHANGE_REQUIRED", "请先修改临时密码，再进入业务端。");
            return false;
        }
        if (Arrays.stream(required.value()).noneMatch(role -> role == user.role())) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "当前账户没有执行此操作的权限。");
            return false;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, user);
        return true;
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of("code", code, "message", message));
    }

    private static String extractBearerToken(String value) {
        if (value == null || !value.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = value.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
