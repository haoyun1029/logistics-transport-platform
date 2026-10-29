package cn.logistics.platform.service;

import cn.logistics.platform.repository.AccountRepository;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import cn.logistics.platform.security.AccountRecord;
import cn.logistics.platform.security.AccountStatus;
import cn.logistics.platform.security.AuthenticationException;
import cn.logistics.platform.security.PasswordHasher;
import cn.logistics.platform.security.UserPrincipal;
import cn.logistics.platform.security.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{3,32}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accounts;
    private final String bootstrapToken;

    public AuthService(AccountRepository accounts,
                       @Value("${app.bootstrap-token:}") String bootstrapToken) {
        this.accounts = accounts;
        this.bootstrapToken = bootstrapToken;
    }

    @Transactional
    public void registerDriver(String username, String password, String displayName, String phone) {
        validateIdentity(username, password, displayName);
        try {
            accounts.insert(username.trim().toLowerCase(Locale.ROOT), PasswordHasher.hash(password),
                    UserRole.DRIVER, AccountStatus.PENDING.name(), displayName.trim(), normalizePhone(phone));
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("该用户名已注册。");
        }
    }

    @Transactional
    public void bootstrapSuperAdmin(String setupToken, String username, String password, String displayName) {
        if (bootstrapToken.isBlank()) throw new AuthenticationException("首次管理员初始化未启用。");
        if (!constantTimeEquals(bootstrapToken, setupToken)) throw new AuthenticationException("初始化凭据无效。");
        if (accounts.countActiveSuperAdmins() != 0) throw new IllegalStateException("系统已初始化超级管理员账户。");
        validateIdentity(username, password, displayName);
        try {
            accounts.insert(username.trim().toLowerCase(Locale.ROOT), PasswordHasher.hash(password),
                    UserRole.SUPER_ADMIN, AccountStatus.APPROVED.name(), displayName.trim(), null);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("该用户名已使用。");
        }
    }

    @Transactional
    public LoginSession login(String username, String password) {
        if (username == null || username.isBlank() || password == null) {
            throw new AuthenticationException("用户名或密码不正确。");
        }
        AccountRecord account = accounts.lockByUsername(username.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new AuthenticationException("用户名或密码不正确。"));
        if (!PasswordHasher.verify(password, account.passwordHash())) {
            throw new AuthenticationException("用户名或密码不正确。");
        }
        if (!AccountStatus.APPROVED.name().equals(account.status())) {
            throw new AuthenticationException("账户尚未通过审核或当前不可用。");
        }
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant expiresAt = Instant.now().plus(12, ChronoUnit.HOURS);
        accounts.insertSession(hashToken(token), account.id(), expiresAt);
        return new LoginSession(token, expiresAt, account.principal());
    }

    public void logout(String token) {
        if (token != null && !token.isBlank()) accounts.deleteSession(hashToken(token));
    }

    public Optional<UserPrincipal> findPrincipal(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return accounts.findPrincipalBySessionHash(hashToken(token), Instant.now());
    }

    @Transactional
    public String resetDriverPassword(long userId, UserPrincipal actor) {
        if (actor.role() != UserRole.SUPER_ADMIN) {
            throw new cn.logistics.platform.security.ForbiddenException("只有超级管理员可以重置司机密码。");
        }
        AccountRecord target = accounts.lockById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在。"));
        if (!UserRole.DRIVER.name().equals(target.role())) throw new IllegalArgumentException("此入口仅用于重置司机密码。");
        if (!java.util.Set.of("APPROVED", "DISABLED").contains(target.status())) {
            throw new IllegalStateException("只能重置已通过审核的司机密码，注册申请请在司机审核页处理。");
        }
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        String temporaryPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        accounts.updatePassword(userId, PasswordHasher.hash(temporaryPassword), true);
        accounts.insertAudit(actor.id(), "DRIVER_PASSWORD_RESET", "USER", Long.toString(userId), "{}");
        return temporaryPassword;
    }

    @Transactional
    public void changePassword(UserPrincipal actor, String currentPassword, String newPassword) {
        AccountRecord target = accounts.lockById(actor.id())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在。"));
        if (!AccountStatus.APPROVED.name().equals(target.status())) throw new IllegalStateException("当前账号不可修改密码。");
        if (currentPassword == null || currentPassword.length() > 128 || !PasswordHasher.verify(currentPassword, target.passwordHash())) {
            throw new IllegalArgumentException("当前密码不正确，请重新输入。");
        }
        if (newPassword == null || newPassword.length() < 10 || newPassword.length() > 128 || newPassword.isBlank()) {
            throw new IllegalArgumentException("新密码须为 10–128 个字符，且不能全部为空格。");
        }
        if (PasswordHasher.verify(newPassword, target.passwordHash())) throw new IllegalArgumentException("新密码不能与当前密码相同。");
        accounts.updatePassword(actor.id(), PasswordHasher.hash(newPassword), false);
        accounts.insertAudit(actor.id(), "PASSWORD_CHANGED", "USER", Long.toString(actor.id()), "{}");
    }

    @Transactional
    public long reviewDriver(long driverId, long reviewerId, boolean approve, String note) {
        if (note != null && note.length() > 300) throw new IllegalArgumentException("审核备注不能超过 300 个字符。");
        if (!approve && (note == null || note.isBlank())) throw new IllegalArgumentException("拒绝申请时请填写原因。");
        String status = approve ? AccountStatus.APPROVED.name() : AccountStatus.REJECTED.name();
        int updated = accounts.updateDriverReview(driverId, status, reviewerId, trimToNull(note));
        if (updated == 0) throw new IllegalStateException("司机不存在或已处理该审核申请。");
        accounts.insertAudit(reviewerId, approve ? "DRIVER_APPROVED" : "DRIVER_REJECTED",
                "USER", Long.toString(driverId), "{\"note\":\"" + jsonEscape(note) + "\"}");
        return driverId;
    }

    public java.util.List<AccountRepository.PendingDriver> pendingDrivers() {
        return accounts.findPendingDrivers();
    }

    public java.util.List<AccountRepository.DriverReview> driverReviews() {
        return accounts.findDriverReviews();
    }

    @Transactional
    public void deleteUser(long userId, long actorId) {
        if (userId == actorId) throw new IllegalArgumentException("不能删除当前登录账户。");
        accounts.insertAudit(actorId, "USER_DELETED", "USER", Long.toString(userId), "{}");
        int updated = accounts.deleteUser(userId);
        if (updated == 0) throw new IllegalStateException("用户不存在、已删除或不能删除超级管理员。");
    }

    private static void validateIdentity(String username, String password, String displayName) {
        if (username == null || !USERNAME_PATTERN.matcher(username.trim()).matches()) {
            throw new IllegalArgumentException("用户名须为 3 到 32 位字母、数字、点、下划线或连字符。");
        }
        if (password == null || password.length() < 10 || password.length() > 128) {
            throw new IllegalArgumentException("密码长度必须为 10 到 128 个字符。");
        }
        if (displayName == null || displayName.isBlank() || displayName.trim().length() > 80) {
            throw new IllegalArgumentException("姓名不能为空且不能超过 80 个字符。");
        }
    }

    private static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String value = phone.trim();
        if (value.length() > 32) throw new IllegalArgumentException("手机号或联系方式不能超过 32 个字符。");
        return value;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256。", exception);
        }
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        if (provided == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        return new String(JsonStringEncoder.getInstance().quoteAsString(value));
    }

    public record LoginSession(String token, Instant expiresAt, UserPrincipal user) {}
}
