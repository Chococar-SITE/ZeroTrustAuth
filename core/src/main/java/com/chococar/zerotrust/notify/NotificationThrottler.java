package com.chococar.zerotrust.notify;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通知速率限制（計劃 6.7）。包裝 {@link Notifier}，防止：
 *
 * <ul>
 *   <li>管理員被大量登入請求 DM 轟炸（通知 DoS）。</li>
 *   <li>因通知疲勞養成「習慣性點擊確認」的危險反射。</li>
 * </ul>
 *
 * <p>同一帳號的登入請求設冷卻（預設 60 秒內最多 1 次）；冷卻期間的重複嘗試
 * <b>合併計數</b>而不逐一發送。{@code notice} / {@code alert} 直接通透；
 * <b>EMERGENCY 警報不受冷卻限制</b>（撤銷、「不是我」一律即時送出）。
 */
public final class NotificationThrottler {

    private final Notifier delegate;
    private final Duration cooldown;
    /** uuid → 上次允許登入請求的時間。 */
    private final Map<UUID, Instant> lastLoginRequest = new ConcurrentHashMap<>();
    /** uuid → 冷卻期間被合併（抑制）的嘗試次數。 */
    private final Map<UUID, AtomicInteger> mergedCount = new ConcurrentHashMap<>();

    public NotificationThrottler(Notifier delegate, Duration cooldown) {
        this.delegate = delegate;
        this.cooldown = cooldown;
    }

    /**
     * 是否允許現在為該帳號發出登入確認請求。
     *
     * @return {@code true} 表示允許（並記錄本次時間）；{@code false} 表示仍在冷卻、本次合併計數。
     */
    public boolean allowLoginRequest(UUID uuid, Instant now) {
        Instant last = lastLoginRequest.get(uuid);
        if (last != null && now.isBefore(last.plus(cooldown))) {
            // 冷卻中：合併計數，不發送。
            mergedCount.computeIfAbsent(uuid, u -> new AtomicInteger()).incrementAndGet();
            return false;
        }
        lastLoginRequest.put(uuid, now);
        return true;
    }

    /** 取得並清除某帳號冷卻期間被合併（抑制）的嘗試次數。 */
    public int drainMergedCount(UUID uuid) {
        AtomicInteger c = mergedCount.remove(uuid);
        return c == null ? 0 : c.get();
    }

    /** 重置某帳號的冷卻狀態（例如驗證成功或登出後）。 */
    public void reset(UUID uuid) {
        lastLoginRequest.remove(uuid);
        mergedCount.remove(uuid);
    }

    // ── 通透轉發 ─────────────────────────────────────────

    /** 低優先資訊通透轉發。 */
    public void notice(String message) {
        delegate.notice(message);
    }

    /** 警報通透轉發；EMERGENCY 本就不受任何冷卻限制（計劃 6.7）。 */
    public void alert(AlertLevel level, String message) {
        delegate.alert(level, message);
    }

    /** 直接委派發出登入確認請求（呼叫端先以 {@link #allowLoginRequest} 判斷冷卻）。 */
    public CompletableFuture<ConfirmResult> requestLoginConfirmation(String playerName, UUID playerUuid, Duration ttl) {
        return delegate.requestLoginConfirmation(playerName, playerUuid, ttl);
    }

    public boolean isAvailable() {
        return delegate.isAvailable();
    }
}
