package com.chococar.zerotrust.auth;

import com.chococar.zerotrust.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedDeviceCacheTest {

    private MutableClock clock;
    private TrustedDeviceCache cache;
    private final UUID uuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        clock = MutableClock.atEpoch();
        cache = new TrustedDeviceCache(Duration.ofMinutes(15));
    }

    @Test
    void withinWindowReturnsFingerprint() {
        cache.record(uuid, "fp-abc", clock.instant());
        clock.advance(Duration.ofMinutes(14));
        assertTrue(cache.trustedFingerprint(uuid, clock.instant()).isPresent());
        assertEquals("fp-abc", cache.trustedFingerprint(uuid, clock.instant()).get());
    }

    @Test
    void expiredAfterWindow() {
        cache.record(uuid, "fp-abc", clock.instant());
        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertFalse(cache.trustedFingerprint(uuid, clock.instant()).isPresent());
    }

    @Test
    void atExactlyWindowIsExpired() {
        cache.record(uuid, "fp-abc", clock.instant());
        clock.advance(Duration.ofMinutes(15));
        assertFalse(cache.trustedFingerprint(uuid, clock.instant()).isPresent());
    }

    @Test
    void unknownUuidEmpty() {
        assertFalse(cache.trustedFingerprint(UUID.randomUUID(), clock.instant()).isPresent());
    }

    @Test
    void recordReplacesFingerprint() {
        cache.record(uuid, "fp-1", clock.instant());
        cache.record(uuid, "fp-2", clock.instant());
        assertEquals("fp-2", cache.trustedFingerprint(uuid, clock.instant()).get());
    }

    @Test
    void clearRemoves() {
        cache.record(uuid, "fp-1", clock.instant());
        cache.clear(uuid);
        assertFalse(cache.trustedFingerprint(uuid, clock.instant()).isPresent());
    }
}
