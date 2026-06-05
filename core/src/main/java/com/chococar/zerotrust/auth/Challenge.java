package com.chococar.zerotrust.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * 單次選項 A 挑戰：32 bytes Nonce，綁定 UUID 與該次連線（計劃 3.2 / 4.2）。
 * 30 秒過期、用後即廢（single-use）。
 *
 * <p>{@code used} 為可變欄位（單次性標記），其餘不可變。
 */
final class Challenge {

    private final UUID uuid;
    private final String connectionId;
    private final byte[] nonce;
    private final Instant expiresAt;
    private volatile boolean used;

    Challenge(UUID uuid, String connectionId, byte[] nonce, Instant expiresAt) {
        this.uuid = uuid;
        this.connectionId = connectionId;
        this.nonce = nonce;
        this.expiresAt = expiresAt;
        this.used = false;
    }

    UUID uuid() { return uuid; }
    String connectionId() { return connectionId; }
    byte[] nonce() { return nonce; }
    Instant expiresAt() { return expiresAt; }
    boolean used() { return used; }
    void markUsed() { this.used = true; }

    boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
