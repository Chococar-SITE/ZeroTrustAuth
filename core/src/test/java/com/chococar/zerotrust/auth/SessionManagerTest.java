package com.chococar.zerotrust.auth;

import com.chococar.zerotrust.audit.AuthMethod;
import com.chococar.zerotrust.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {

    private MutableClock clock;
    private SessionManager sm;
    private final UUID uuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        sm = new SessionManager(clock, Duration.ofHours(4));
    }

    @Test
    void freezeThenVerifyThenExpire() {
        sm.freeze(uuid, "conn-1");
        assertTrue(sm.isFrozen(uuid));
        assertFalse(sm.isVerified(uuid));
        assertEquals(SessionState.FROZEN, sm.getState(uuid));

        sm.verify(uuid, AuthMethod.SIGNATURE_A);
        assertTrue(sm.isVerified(uuid));
        assertFalse(sm.isFrozen(uuid));
        assertEquals(AuthMethod.SIGNATURE_A, sm.getMethod(uuid));
        assertEquals("conn-1", sm.getConnectionId(uuid));

        // 未到期前 tickExpire 不影響。
        clock.advance(Duration.ofHours(3));
        assertTrue(sm.tickExpire(clock.instant()).isEmpty());
        assertTrue(sm.isVerified(uuid));

        // 超過 4 小時 TTL → EXPIRED。
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        List<UUID> expired = sm.tickExpire(clock.instant());
        assertEquals(1, expired.size());
        assertTrue(expired.contains(uuid));
        assertEquals(SessionState.EXPIRED, sm.getState(uuid));
        assertFalse(sm.isVerified(uuid));
    }

    @Test
    void tickExpireIsIdempotent() {
        sm.freeze(uuid, "c");
        sm.verify(uuid, AuthMethod.SIGNATURE_A);
        clock.advance(Duration.ofHours(5));
        assertEquals(1, sm.tickExpire(clock.instant()).size());
        // 第二次不應再回報（已是 EXPIRED）。
        assertTrue(sm.tickExpire(clock.instant()).isEmpty());
    }

    @Test
    void revoke() {
        sm.freeze(uuid, "c");
        sm.verify(uuid, AuthMethod.SIGNATURE_A);
        sm.revoke(uuid);
        assertEquals(SessionState.REVOKED, sm.getState(uuid));
        assertFalse(sm.isVerified(uuid));
    }

    @Test
    void getAllVerified() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        sm.freeze(a, "ca");
        sm.verify(a, AuthMethod.SIGNATURE_A);
        sm.freeze(b, "cb");
        sm.verify(b, AuthMethod.OUT_OF_BAND_B);
        sm.freeze(c, "cc"); // 僅凍結，未驗證。

        assertEquals(2, sm.getAllVerified().size());
        assertTrue(sm.getAllVerified().contains(a));
        assertTrue(sm.getAllVerified().contains(b));
        assertFalse(sm.getAllVerified().contains(c));
    }

    @Test
    void quitRemovesSession() {
        sm.freeze(uuid, "c");
        sm.verify(uuid, AuthMethod.SIGNATURE_A);
        sm.quit(uuid);
        assertFalse(sm.isVerified(uuid));
        assertEquals(null, sm.getState(uuid));
    }

    @Test
    void clearAll() {
        sm.freeze(uuid, "c");
        sm.verify(uuid, AuthMethod.SIGNATURE_A);
        sm.clearAll();
        assertTrue(sm.getAllVerified().isEmpty());
    }

    @Test
    void reFreezeResetsVerifiedState() {
        sm.freeze(uuid, "c1");
        sm.verify(uuid, AuthMethod.SIGNATURE_A);
        // 重新驗證流程：重新凍結。
        sm.freeze(uuid, "c2");
        assertTrue(sm.isFrozen(uuid));
        assertFalse(sm.isVerified(uuid));
        assertEquals("c2", sm.getConnectionId(uuid));
        assertEquals(null, sm.getMethod(uuid));
    }
}
