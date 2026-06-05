package com.chococar.zerotrust.auth;

import com.chococar.zerotrust.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeManagerTest {

    private MutableClock clock;
    private ChallengeManager cm;
    private final UUID uuid = UUID.randomUUID();
    private static final String CONN = "conn-1";

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        cm = new ChallengeManager(clock);
    }

    @Test
    void issueAndConsumeSucceeds() {
        byte[] nonce = cm.issue(uuid, CONN);
        assertTrue(cm.consume(uuid, CONN, nonce));
    }

    @Test
    void nonceIs32Bytes() {
        byte[] nonce = cm.issue(uuid, CONN);
        assertTrue(nonce.length == 32);
    }

    @Test
    void expiredAfter30sFails() {
        byte[] nonce = cm.issue(uuid, CONN);
        clock.advance(Duration.ofSeconds(31));
        assertFalse(cm.consume(uuid, CONN, nonce));
    }

    @Test
    void atExactly30sIsExpired() {
        byte[] nonce = cm.issue(uuid, CONN);
        clock.advance(Duration.ofSeconds(30)); // expiresAt 為 now+30s，>= 即過期。
        assertFalse(cm.consume(uuid, CONN, nonce));
    }

    @Test
    void reuseFails() {
        byte[] nonce = cm.issue(uuid, CONN);
        assertTrue(cm.consume(uuid, CONN, nonce));
        assertFalse(cm.consume(uuid, CONN, nonce), "single-use: 第二次必須失敗");
    }

    @Test
    void wrongConnectionIdFails() {
        byte[] nonce = cm.issue(uuid, CONN);
        assertFalse(cm.consume(uuid, "other-conn", nonce));
    }

    @Test
    void wrongUuidFails() {
        byte[] nonce = cm.issue(uuid, CONN);
        assertFalse(cm.consume(UUID.randomUUID(), CONN, nonce));
    }

    @Test
    void wrongNonceFails() {
        cm.issue(uuid, CONN);
        byte[] bad = new byte[32];
        assertFalse(cm.consume(uuid, CONN, bad));
    }

    @Test
    void reissueReplacesPrevious() {
        byte[] first = cm.issue(uuid, CONN);
        byte[] second = cm.issue(uuid, CONN);
        // 舊 Nonce 已被取代，無法消費。
        assertFalse(cm.consume(uuid, CONN, first));
        assertTrue(cm.consume(uuid, CONN, second));
    }

    @Test
    void nullArgumentsFail() {
        byte[] nonce = cm.issue(uuid, CONN);
        assertFalse(cm.consume(null, CONN, nonce));
        assertFalse(cm.consume(uuid, null, nonce));
        assertFalse(cm.consume(uuid, CONN, null));
    }
}
