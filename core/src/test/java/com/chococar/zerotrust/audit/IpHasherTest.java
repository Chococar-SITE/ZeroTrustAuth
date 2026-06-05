package com.chococar.zerotrust.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IpHasherTest {

    private final byte[] secret = "server-side-salt-abcdef0123456789".getBytes();
    private final byte[] secret2 = "a-different-salt-xxxxxxxxxxxxxxxxx".getBytes();

    @Test
    void deterministicForSameInput() {
        String a = IpHasher.hmacSha256Hex(secret, "203.0.113.5");
        String b = IpHasher.hmacSha256Hex(secret, "203.0.113.5");
        assertEquals(a, b);
    }

    @Test
    void differentIpsDiffer() {
        String a = IpHasher.hmacSha256Hex(secret, "203.0.113.5");
        String b = IpHasher.hmacSha256Hex(secret, "203.0.113.6");
        assertNotEquals(a, b);
    }

    @Test
    void saltDependent() {
        String a = IpHasher.hmacSha256Hex(secret, "203.0.113.5");
        String b = IpHasher.hmacSha256Hex(secret2, "203.0.113.5");
        assertNotEquals(a, b, "不同密鑰鹽必須產生不同雜湊");
    }

    @Test
    void notEqualToRawIp() {
        String ip = "203.0.113.5";
        String hash = IpHasher.hmacSha256Hex(secret, ip);
        assertNotEquals(ip, hash);
        // 形式為 64 字元小寫十六進位（HMAC-SHA256）。
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void notEqualToBareSha256() throws Exception {
        // 確認不是裸 sha256(ip)。
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] bare = md.digest("203.0.113.5".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String bareHex = com.chococar.zerotrust.util.Hex.encode(bare);
        String hmac = IpHasher.hmacSha256Hex(secret, "203.0.113.5");
        assertNotEquals(bareHex, hmac);
    }

    @Test
    void emptySecretRejected() {
        assertThrows(IllegalArgumentException.class, () -> IpHasher.hmacSha256Hex(new byte[0], "1.2.3.4"));
    }

    @Test
    void nullIpRejected() {
        assertThrows(IllegalArgumentException.class, () -> IpHasher.hmacSha256Hex(secret, null));
    }
}
