package cn.logistics.platform.security;

public record UserPrincipal(
        long id,
        String username,
        String displayName,
        UserRole role,
        boolean passwordChangeRequired) {
    public UserPrincipal(long id, String username, String displayName, UserRole role) {
        this(id, username, displayName, role, false);
    }
}
