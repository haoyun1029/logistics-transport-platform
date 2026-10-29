package cn.logistics.platform.repository;

import cn.logistics.platform.security.AccountRecord;
import cn.logistics.platform.security.UserPrincipal;
import cn.logistics.platform.security.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class AccountRepository {

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AccountRecord> findByUsername(String username) {
        return findByUsername(username, false);
    }

    public Optional<AccountRecord> lockByUsername(String username) {
        return findByUsername(username, true);
    }

    private Optional<AccountRecord> findByUsername(String username, boolean lock) {
        return jdbc.query("""
                SELECT id, username, password_hash, role, account_status, display_name, phone, password_change_required
                FROM users WHERE username = ?
                """ + (lock ? " FOR UPDATE" : ""), rs -> rs.next() ? Optional.of(new AccountRecord(
                rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                rs.getString("role"), rs.getString("account_status"),
                rs.getString("display_name"), rs.getString("phone"), rs.getBoolean("password_change_required"))) : Optional.empty(), username);
    }

    public Optional<AccountRecord> findById(long userId) {
        return findById(userId, false);
    }

    public Optional<AccountRecord> lockById(long userId) {
        return findById(userId, true);
    }

    private Optional<AccountRecord> findById(long userId, boolean lock) {
        return jdbc.query("""
                SELECT id, username, password_hash, role, account_status, display_name, phone, password_change_required
                FROM users WHERE id = ?
                """ + (lock ? " FOR UPDATE" : ""), rs -> rs.next() ? Optional.of(new AccountRecord(
                rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                rs.getString("role"), rs.getString("account_status"),
                rs.getString("display_name"), rs.getString("phone"), rs.getBoolean("password_change_required"))) : Optional.empty(), userId);
    }

    public Optional<UserPrincipal> findPrincipalBySessionHash(String tokenHash, Instant now) {
        return jdbc.query("""
                SELECT u.id, u.username, u.display_name, u.role, u.password_change_required
                FROM api_sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.expires_at > ? AND u.account_status = 'APPROVED'
                """, rs -> rs.next() ? Optional.of(new UserPrincipal(
                rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                UserRole.valueOf(rs.getString("role")), rs.getBoolean("password_change_required"))) : Optional.empty(),
                tokenHash, Timestamp.from(now));
    }

    public long countActiveSuperAdmins() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role = 'SUPER_ADMIN' AND account_status = 'APPROVED'", Long.class);
        return count == null ? 0 : count;
    }

    public long insert(String username, String passwordHash, UserRole role,
                       String status, String displayName, String phone) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO users (username, password_hash, role, account_status, display_name, phone)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, role.name());
            statement.setString(4, status);
            statement.setString(5, displayName);
            statement.setString(6, phone);
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        if (id == null) throw new IllegalStateException("未能读取新建账户编号。");
        return id.longValue();
    }

    public void insertSession(String tokenHash, long userId, Instant expiresAt) {
        jdbc.update("INSERT INTO api_sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)",
                tokenHash, userId, Timestamp.from(expiresAt));
    }

    public void deleteSession(String tokenHash) {
        jdbc.update("DELETE FROM api_sessions WHERE token_hash = ?", tokenHash);
    }

    public void updatePassword(long userId, String passwordHash, boolean changeRequired) {
        jdbc.update("UPDATE users SET password_hash=?,password_change_required=? WHERE id=?",
                passwordHash, changeRequired, userId);
        jdbc.update("DELETE FROM api_sessions WHERE user_id=?", userId);
    }

    public int updateDriverReview(long driverId, String status, long reviewerId, String note) {
        return jdbc.update("""
                UPDATE users SET account_status = ?, reviewed_at = CURRENT_TIMESTAMP(3),
                    reviewed_by = ?, review_note = ?
                WHERE id = ? AND role = 'DRIVER' AND account_status = 'PENDING'
                """, status, reviewerId, note, driverId);
    }

    public int deleteUser(long userId) {
        if(jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,userId).isEmpty())return 0;
        if(!jdbc.queryForList("SELECT id FROM transport_tasks WHERE driver_id=? AND task_status IN ('PENDING_ACCEPTANCE','IN_TRANSIT') FOR UPDATE",Long.class,userId).isEmpty())
            throw new IllegalStateException("司机存在待接单或运输中任务，不能删除。");
        jdbc.update("DELETE FROM api_sessions WHERE user_id = ?", userId);
        return jdbc.update("""
                UPDATE users SET account_status = 'DELETED',
                    username = CONCAT('deleted-', id), password_hash = 'REVOKED',
                    display_name = '已删除用户', phone = NULL, review_note = NULL
                WHERE id = ? AND role <> 'SUPER_ADMIN' AND account_status <> 'DELETED'
                """, userId);
    }

    public java.util.List<PendingDriver> findPendingDrivers() {
        return jdbc.query("""
                SELECT id, username, display_name, phone, created_at
                FROM users WHERE role = 'DRIVER' AND account_status = 'PENDING'
                ORDER BY created_at, id
                """, (rs, rowNumber) -> new PendingDriver(rs.getLong("id"),
                rs.getString("username"), rs.getString("display_name"), rs.getString("phone"),
                rs.getTimestamp("created_at").toInstant()));
    }

    public record PendingDriver(long id, String username, String displayName,
                                String phone, Instant createdAt) {}

    public java.util.List<DriverReview> findDriverReviews() {
        return jdbc.query("""
                SELECT u.id, u.username, u.display_name, u.account_status, u.review_note,
                       u.reviewed_at, r.display_name AS reviewer_name
                FROM users u LEFT JOIN users r ON r.id = u.reviewed_by
                WHERE u.role = 'DRIVER' AND u.account_status IN ('APPROVED', 'REJECTED')
                    AND u.reviewed_at IS NOT NULL
                ORDER BY u.reviewed_at DESC, u.id DESC LIMIT 100
                """, (rs, rowNumber) -> new DriverReview(rs.getLong("id"), rs.getString("username"),
                rs.getString("display_name"), rs.getString("account_status"), rs.getString("review_note"),
                rs.getTimestamp("reviewed_at").toInstant(), rs.getString("reviewer_name")));
    }

    public record DriverReview(long id, String username, String displayName, String status,
                               String note, Instant reviewedAt, String reviewerName) {}

    public void insertAudit(long actorId, String action, String entityType, String entityId, String detailsJson) {
        jdbc.update("""
                INSERT INTO audit_logs (actor_user_id, action, entity_type, entity_id, details_json)
                VALUES (?, ?, ?, ?, ?)
                """, actorId, action, entityType, entityId, detailsJson);
    }
}
