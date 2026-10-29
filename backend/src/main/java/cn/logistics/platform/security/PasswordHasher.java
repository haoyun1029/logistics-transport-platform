package cn.logistics.platform.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String password) {
        if (password == null || password.length() < 10 || password.length() > 128) {
            throw new IllegalArgumentException("密码长度必须为 10 到 128 个字符。");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "$pbkdf2-sha256$" + ITERATIONS + "$"
                + encoder.encodeToString(salt) + "$" + encoder.encodeToString(derived);
    }

    public static boolean verify(String password, String encoded) {
        if (password == null || encoded == null) return false;
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 5 || !parts[1].equals("pbkdf2-sha256")) return false;
        try {
            int iterations = Integer.parseInt(parts[2]);
            if (iterations < 100_000 || iterations > 2_000_000) return false;
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] salt = decoder.decode(parts[3]);
            byte[] expected = decoder.decode(parts[4]);
            byte[] actual = derive(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec specification = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("当前 Java 运行时不支持密码派生算法。", exception);
        } finally {
            specification.clearPassword();
        }
    }
}
