package cn.logistics.platform.security;

public record AccountRecord(
        long id,
        String username,
        String passwordHash,
        String role,
        String status,
        String displayName,
        String phone,
        boolean passwordChangeRequired) {

    public AccountRecord(long id, String username, String passwordHash, String role, String status,
                         String displayName, String phone) {
        this(id, username, passwordHash, role, status, displayName, phone, false);
    }

    public UserPrincipal principal() {
        return new UserPrincipal(id, username, displayName, UserRole.valueOf(role), passwordChangeRequired);
    }
}
