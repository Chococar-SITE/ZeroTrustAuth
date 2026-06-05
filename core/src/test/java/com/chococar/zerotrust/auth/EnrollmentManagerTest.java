package com.chococar.zerotrust.auth;

import com.chococar.zerotrust.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrollmentManagerTest {

    private MutableClock clock;
    private EnrollmentManager em;
    private final UUID uuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        em = new EnrollmentManager(clock, Duration.ofMinutes(10), 5);
    }

    @Test
    void issueProduces128BitHex() {
        String token = em.issue(uuid);
        // 16 bytes → 32 hex chars。
        assertEquals(32, token.length());
        assertTrue(token.matches("[0-9a-f]{32}"));
    }

    @Test
    void issueRedeemSuccess() {
        String token = em.issue(uuid);
        assertEquals(EnrollmentManager.Result.SUCCESS, em.redeem(uuid, token));
    }

    @Test
    void noTokenWhenNeverIssued() {
        assertEquals(EnrollmentManager.Result.NO_TOKEN, em.redeem(uuid, "deadbeef"));
    }

    @Test
    void expiredAfterTtl() {
        String token = em.issue(uuid);
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertEquals(EnrollmentManager.Result.EXPIRED, em.redeem(uuid, token));
    }

    @Test
    void wrongCodeIsInvalid() {
        em.issue(uuid);
        assertEquals(EnrollmentManager.Result.INVALID, em.redeem(uuid, "00000000000000000000000000000000"));
    }

    @Test
    void singleUse() {
        String token = em.issue(uuid);
        assertEquals(EnrollmentManager.Result.SUCCESS, em.redeem(uuid, token));
        // 用後即廢 → 變成 NO_TOKEN。
        assertEquals(EnrollmentManager.Result.NO_TOKEN, em.redeem(uuid, token));
    }

    @Test
    void lockAfterMaxAttempts() {
        em.issue(uuid);
        String wrong = "00000000000000000000000000000000";
        // 5 次錯誤後達上限。
        for (int i = 0; i < 5; i++) {
            assertEquals(EnrollmentManager.Result.INVALID, em.redeem(uuid, wrong));
        }
        // 第 6 次（含正確碼）一律 LOCKED。
        assertEquals(EnrollmentManager.Result.LOCKED, em.redeem(uuid, wrong));
    }

    @Test
    void lockedEvenWithCorrectCode() {
        String token = em.issue(uuid);
        String wrong = "00000000000000000000000000000000";
        for (int i = 0; i < 5; i++) {
            em.redeem(uuid, wrong);
        }
        // 已鎖定後即使提供正確碼也拒絕。
        assertEquals(EnrollmentManager.Result.LOCKED, em.redeem(uuid, token));
    }

    @Test
    void reissueResetsAttemptCounter() {
        em.issue(uuid);
        String wrong = "00000000000000000000000000000000";
        for (int i = 0; i < 4; i++) {
            em.redeem(uuid, wrong);
        }
        // 重新發碼應重置計數，新碼可成功兌換。
        String fresh = em.issue(uuid);
        assertEquals(EnrollmentManager.Result.SUCCESS, em.redeem(uuid, fresh));
    }

    @Test
    void tokensAreUniquePerIssue() {
        String a = em.issue(uuid);
        String b = em.issue(uuid);
        assertNotEquals(a, b);
    }
}
