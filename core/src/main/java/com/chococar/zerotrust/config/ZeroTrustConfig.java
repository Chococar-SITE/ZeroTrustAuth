package com.chococar.zerotrust.config;

import java.time.Duration;
import java.util.Objects;

/**
 * 不可變設定（計劃 7.3）。平台層從 YAML 讀取後建構此物件交給核心；
 * 核心不依賴任何 YAML 函式庫。<b>秘密（Bot Token、IP HMAC 密鑰鹽）不在此處</b>，
 * 一律由環境變數注入（計劃 6.2）。
 */
public final class ZeroTrustConfig {

    // settings.*
    private final Duration sessionTtl;
    private final int maxAttempts;
    private final Duration optionATimeout;
    private final Duration optionBTokenTtl;
    private final Duration enrollmentTokenTtl;
    private final int enrollmentMaxAttempts;
    private final boolean allowFallback;
    private final int freezePacketLimitPerSecond;
    private final Duration trustedDeviceWindow;
    private final boolean stripVanillaOp;
    private final boolean failClosed;

    // security.*
    private final String signatureDomain;
    private final boolean startupSelfTest;

    // discord.*
    private final Duration notifyCooldown;

    // logging.*
    private final int logRetentionDays;

    private ZeroTrustConfig(Builder b) {
        this.sessionTtl = b.sessionTtl;
        this.maxAttempts = b.maxAttempts;
        this.optionATimeout = b.optionATimeout;
        this.optionBTokenTtl = b.optionBTokenTtl;
        this.enrollmentTokenTtl = b.enrollmentTokenTtl;
        this.enrollmentMaxAttempts = b.enrollmentMaxAttempts;
        this.allowFallback = b.allowFallback;
        this.freezePacketLimitPerSecond = b.freezePacketLimitPerSecond;
        this.trustedDeviceWindow = b.trustedDeviceWindow;
        this.stripVanillaOp = b.stripVanillaOp;
        this.failClosed = b.failClosed;
        this.signatureDomain = Objects.requireNonNull(b.signatureDomain, "signatureDomain");
        this.startupSelfTest = b.startupSelfTest;
        this.notifyCooldown = b.notifyCooldown;
        this.logRetentionDays = b.logRetentionDays;
    }

    public Duration sessionTtl() { return sessionTtl; }
    public int maxAttempts() { return maxAttempts; }
    public Duration optionATimeout() { return optionATimeout; }
    public Duration optionBTokenTtl() { return optionBTokenTtl; }
    public Duration enrollmentTokenTtl() { return enrollmentTokenTtl; }
    public int enrollmentMaxAttempts() { return enrollmentMaxAttempts; }
    public boolean allowFallback() { return allowFallback; }
    public int freezePacketLimitPerSecond() { return freezePacketLimitPerSecond; }
    public Duration trustedDeviceWindow() { return trustedDeviceWindow; }
    public boolean stripVanillaOp() { return stripVanillaOp; }
    public boolean failClosed() { return failClosed; }
    public String signatureDomain() { return signatureDomain; }
    public boolean startupSelfTest() { return startupSelfTest; }
    public Duration notifyCooldown() { return notifyCooldown; }
    public int logRetentionDays() { return logRetentionDays; }

    public static Builder builder() { return new Builder(); }

    /** 以計劃 7.3 預設值建立。 */
    public static ZeroTrustConfig defaults() { return builder().build(); }

    /** 預設值對應計劃 7.3。 */
    public static final class Builder {
        private Duration sessionTtl = Duration.ofHours(4);
        private int maxAttempts = 3;
        private Duration optionATimeout = Duration.ofSeconds(10);
        private Duration optionBTokenTtl = Duration.ofMinutes(5);
        private Duration enrollmentTokenTtl = Duration.ofMinutes(10);
        private int enrollmentMaxAttempts = 5;
        private boolean allowFallback = true;
        private int freezePacketLimitPerSecond = 20;
        private Duration trustedDeviceWindow = Duration.ofMinutes(15);
        private boolean stripVanillaOp = true;
        private boolean failClosed = true;
        private String signatureDomain = "MC-ZEROTRUST-AUTH-v1:";
        private boolean startupSelfTest = true;
        private Duration notifyCooldown = Duration.ofSeconds(60);
        private int logRetentionDays = 90;

        public Builder sessionTtl(Duration v) { this.sessionTtl = v; return this; }
        public Builder maxAttempts(int v) { this.maxAttempts = v; return this; }
        public Builder optionATimeout(Duration v) { this.optionATimeout = v; return this; }
        public Builder optionBTokenTtl(Duration v) { this.optionBTokenTtl = v; return this; }
        public Builder enrollmentTokenTtl(Duration v) { this.enrollmentTokenTtl = v; return this; }
        public Builder enrollmentMaxAttempts(int v) { this.enrollmentMaxAttempts = v; return this; }
        public Builder allowFallback(boolean v) { this.allowFallback = v; return this; }
        public Builder freezePacketLimitPerSecond(int v) { this.freezePacketLimitPerSecond = v; return this; }
        public Builder trustedDeviceWindow(Duration v) { this.trustedDeviceWindow = v; return this; }
        public Builder stripVanillaOp(boolean v) { this.stripVanillaOp = v; return this; }
        public Builder failClosed(boolean v) { this.failClosed = v; return this; }
        public Builder signatureDomain(String v) { this.signatureDomain = v; return this; }
        public Builder startupSelfTest(boolean v) { this.startupSelfTest = v; return this; }
        public Builder notifyCooldown(Duration v) { this.notifyCooldown = v; return this; }
        public Builder logRetentionDays(int v) { this.logRetentionDays = v; return this; }

        public ZeroTrustConfig build() { return new ZeroTrustConfig(this); }
    }
}
