package com.chococar.zerotrust.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** 可手動推進的 {@link Clock}，供測試控制時間。 */
public final class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public static MutableClock atEpoch() {
        return new MutableClock(Instant.parse("2026-06-05T00:00:00Z"));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void advance(Duration d) {
        this.instant = this.instant.plus(d);
    }

    public void setInstant(Instant i) {
        this.instant = i;
    }
}
