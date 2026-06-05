package com.chococar.zerotrust.auth;

import com.chococar.zerotrust.util.ConstantTime;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 選項 A 挑戰（Nonce）的產生與消費（計劃 3.2 / 4.2）。
 *
 * <ul>
 *   <li>Nonce = 32 bytes 安全亂數</li>
 *   <li>TTL = 30 秒（<b>硬編碼</b>，非設定項）</li>
 *   <li>每個 UUID 僅保留一個有效挑戰（重新發出即取代舊的）</li>
 *   <li>消費條件：存在、Nonce 常數時間相等、連線 ID 相符、未過期、未使用過</li>
 *   <li>用後即廢（single-use）</li>
 * </ul>
 *
 * <p>狀態以 {@link ConcurrentHashMap} 保存，可從任意執行緒安全存取。
 */
public final class ChallengeManager {

    /** 計劃 4.2：Nonce 有效期固定 30 秒。 */
    public static final Duration NONCE_TTL = Duration.ofSeconds(30);
    private static final int NONCE_BYTES = 32;

    private final Clock clock;
    private final SecureRandom secureRandom;
    private final Map<UUID, Challenge> challenges = new ConcurrentHashMap<>();

    public ChallengeManager(Clock clock) {
        this.clock = clock;
        this.secureRandom = new SecureRandom();
    }

    /**
     * 發出新挑戰並回傳 32 bytes Nonce；同一 UUID 既有挑戰會被取代。
     */
    public byte[] issue(UUID uuid, String connectionId) {
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        Instant expiresAt = clock.instant().plus(NONCE_TTL);
        challenges.put(uuid, new Challenge(uuid, connectionId, nonce.clone(), expiresAt));
        return nonce;
    }

    /**
     * 消費挑戰。僅在所有條件滿足時成功並標記為已使用；否則回傳 {@code false}。
     */
    public boolean consume(UUID uuid, String connectionId, byte[] nonce) {
        if (uuid == null || connectionId == null || nonce == null) {
            return false;
        }
        Challenge c = challenges.get(uuid);
        if (c == null) {
            return false;
        }
        // 已過期 → 視為失敗並清除。
        if (c.isExpired(clock.instant())) {
            challenges.remove(uuid, c);
            return false;
        }
        if (c.used()) {
            return false;
        }
        if (!connectionId.equals(c.connectionId())) {
            return false;
        }
        if (!ConstantTime.equals(nonce, c.nonce())) {
            return false;
        }
        // 全部通過：單次性標記並移除（用後即廢）。
        c.markUsed();
        challenges.remove(uuid, c);
        return true;
    }

    /** 清除指定 UUID 的挑戰（例如登出或驗證完成）。 */
    public void clear(UUID uuid) {
        if (uuid != null) {
            challenges.remove(uuid);
        }
    }

    /** 移除所有已過期的挑戰。 */
    public void purgeExpired(Instant now) {
        for (Iterator<Map.Entry<UUID, Challenge>> it = challenges.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Challenge> e = it.next();
            if (e.getValue().isExpired(now)) {
                it.remove();
            }
        }
    }

    /** 測試 / 診斷用：目前是否存在某 UUID 的有效（未消費）挑戰。 */
    boolean hasActive(UUID uuid) {
        Challenge c = challenges.get(uuid);
        return c != null && !c.used() && !c.isExpired(clock.instant());
    }
}
