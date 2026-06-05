package com.chococar.zerotrust.notify;

import com.chococar.zerotrust.support.FakeNotifier;
import com.chococar.zerotrust.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationThrottlerTest {

    private MutableClock clock;
    private FakeNotifier delegate;
    private NotificationThrottler throttler;
    private final UUID uuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        delegate = new FakeNotifier();
        throttler = new NotificationThrottler(delegate, Duration.ofSeconds(60));
    }

    @Test
    void firstRequestAllowed() {
        assertTrue(throttler.allowLoginRequest(uuid, clock.instant()));
    }

    @Test
    void secondRequestWithinCooldownSuppressed() {
        assertTrue(throttler.allowLoginRequest(uuid, clock.instant()));
        clock.advance(Duration.ofSeconds(30));
        assertFalse(throttler.allowLoginRequest(uuid, clock.instant()));
        // 合併計數應記錄被抑制的嘗試。
        assertEquals(1, throttler.drainMergedCount(uuid));
    }

    @Test
    void requestAllowedAfterCooldown() {
        assertTrue(throttler.allowLoginRequest(uuid, clock.instant()));
        clock.advance(Duration.ofSeconds(61));
        assertTrue(throttler.allowLoginRequest(uuid, clock.instant()));
    }

    @Test
    void mergedCountAccumulates() {
        throttler.allowLoginRequest(uuid, clock.instant());
        clock.advance(Duration.ofSeconds(10));
        throttler.allowLoginRequest(uuid, clock.instant());
        clock.advance(Duration.ofSeconds(10));
        throttler.allowLoginRequest(uuid, clock.instant());
        assertEquals(2, throttler.drainMergedCount(uuid));
        // drain 後清零。
        assertEquals(0, throttler.drainMergedCount(uuid));
    }

    @Test
    void resetClearsCooldown() {
        throttler.allowLoginRequest(uuid, clock.instant());
        throttler.reset(uuid);
        // reset 後立即又可請求。
        assertTrue(throttler.allowLoginRequest(uuid, clock.instant()));
    }

    @Test
    void emergencyAlertBypassesAndPassesThrough() {
        // 警報一律通透（EMERGENCY 不受冷卻）。
        throttler.alert(AlertLevel.EMERGENCY, "撤銷警報");
        throttler.alert(AlertLevel.EMERGENCY, "不是我");
        assertEquals(2, delegate.alertCount(AlertLevel.EMERGENCY));
    }

    @Test
    void noticePassesThrough() {
        throttler.notice("hello");
        assertEquals(1, delegate.notices.size());
    }

    @Test
    void perUuidIndependentCooldown() {
        UUID other = UUID.randomUUID();
        assertTrue(throttler.allowLoginRequest(uuid, clock.instant()));
        // 不同帳號互不影響冷卻。
        assertTrue(throttler.allowLoginRequest(other, clock.instant()));
    }
}
