package com.chococar.zerotrust.auth;

import com.chococar.zerotrust.util.ConstantTime;
import com.chococar.zerotrust.util.Hex;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enrollment（首次信任建立）一次性註冊碼的產生與兌換（計劃 3.5 / 安全不變式 6）。
 *
 * <ul>
 *   <li>註冊碼 = 16 bytes 安全亂數 → 小寫十六進位（128-bit 高熵）。</li>
 *   <li>每個 UUID 僅保留一個有效註冊碼（重新發出即取代）。</li>
 *   <li>TTL = {@code enrollment_token_ttl}（預設 10 分鐘）。</li>
 *   <li>用後即廢（single-use）。</li>
 *   <li>輸入錯誤<b>速率限制</b>：累計達 {@code enrollment_max_attempts} 即鎖定（觸發警報）。</li>
 *   <li>常數時間比較，避免以時間側通道洩漏註冊碼。</li>
 * </ul>
 *
 * <p>註冊碼來源僅限主控台（由引擎強制），攻擊者即使盜用帳號亦無法取得。
 */
public final class EnrollmentManager {

    private static final int TOKEN_BYTES = 16; // 128-bit

    /** 兌換結果。 */
    public enum Result {
        /** 成功（已作廢註冊碼並重置嘗試計數）。 */
        SUCCESS,
        /** 註冊碼錯誤（已累計一次嘗試）。 */
        INVALID,
        /** 註冊碼已過期。 */
        EXPIRED,
        /** 錯誤次數達上限，已鎖定。 */
        LOCKED,
        /** 該帳號目前無有效註冊碼。 */
        NO_TOKEN
    }

    private final Clock clock;
    private final Duration tokenTtl;
    private final int maxAttempts;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<UUID, Token> tokens = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();

    public EnrollmentManager(Clock clock, Duration tokenTtl, int maxAttempts) {
        this.clock = clock;
        this.tokenTtl = tokenTtl;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 為指定帳號產生一次性註冊碼（取代既有），並重置該帳號的錯誤計數。
     *
     * @return 128-bit 小寫十六進位註冊碼
     */
    public String issue(UUID uuid) {
        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String code = Hex.encode(raw);
        Instant expiresAt = clock.instant().plus(tokenTtl);
        tokens.put(uuid, new Token(code, expiresAt));
        attempts.remove(uuid); // 重新發碼即解除先前的速率限制計數。
        return code;
    }

    /**
     * 兌換註冊碼。
     *
     * <p>判定順序：鎖定 → 無碼 → 過期 → 比對。比對失敗累計嘗試（可能轉為鎖定，
     * 但本次仍回傳 {@code INVALID}）；比對成功則作廢註冊碼並重置計數。
     */
    public synchronized Result redeem(UUID uuid, String code) {
        if (uuid == null) {
            return Result.INVALID;
        }
        if (attempts.getOrDefault(uuid, 0) >= maxAttempts) {
            return Result.LOCKED;
        }
        Token t = tokens.get(uuid);
        if (t == null) {
            return Result.NO_TOKEN;
        }
        if (!clock.instant().isBefore(t.expiresAt)) {
            tokens.remove(uuid, t);
            return Result.EXPIRED;
        }
        if (code == null || !ConstantTime.equals(code, t.code)) {
            attempts.merge(uuid, 1, Integer::sum);
            return Result.INVALID;
        }
        // 成功：用後即廢並重置速率限制。
        tokens.remove(uuid, t);
        attempts.remove(uuid);
        return Result.SUCCESS;
    }

    /** 移除所有已過期的註冊碼。 */
    public void purgeExpired(Instant now) {
        tokens.entrySet().removeIf(e -> !now.isBefore(e.getValue().expiresAt));
    }

    /** 測試 / 診斷用：目前該帳號是否有有效（未過期）註冊碼。 */
    boolean hasActive(UUID uuid) {
        Token t = tokens.get(uuid);
        return t != null && clock.instant().isBefore(t.expiresAt);
    }

    private static final class Token {
        final String code;
        final Instant expiresAt;

        Token(String code, Instant expiresAt) {
            this.code = code;
            this.expiresAt = expiresAt;
        }
    }
}
