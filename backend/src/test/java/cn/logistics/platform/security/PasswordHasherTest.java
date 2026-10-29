package cn.logistics.platform.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    @Test
    void storesSaltedPbkdf2HashAndVerifiesOnlyTheOriginalPassword() {
        String firstHash = PasswordHasher.hash("correct-horse-42");
        String secondHash = PasswordHasher.hash("correct-horse-42");

        assertTrue(firstHash.startsWith("$pbkdf2-sha256$"));
        assertFalse(firstHash.equals(secondHash));
        assertTrue(PasswordHasher.verify("correct-horse-42", firstHash));
        assertFalse(PasswordHasher.verify("another-password", firstHash));
    }

    @Test
    void rejectsWeakPasswordsAndMalformedHashes() {
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.hash("short"));
        assertFalse(PasswordHasher.verify("anything", "not-a-password-hash"));
    }
}
