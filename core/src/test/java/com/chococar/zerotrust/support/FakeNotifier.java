package com.chococar.zerotrust.support;

import com.chococar.zerotrust.notify.AlertLevel;
import com.chococar.zerotrust.notify.ConfirmResult;
import com.chococar.zerotrust.notify.Notifier;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 測試用 {@link Notifier}：記錄 notice / alert，並對登入確認回傳可程式化的 future。
 */
public final class FakeNotifier implements Notifier {

    public final List<String> notices = new CopyOnWriteArrayList<>();
    public final List<Alert> alerts = new CopyOnWriteArrayList<>();
    public final List<LoginRequest> loginRequests = new CopyOnWriteArrayList<>();

    private volatile boolean available = true;
    /** 下一個登入確認 future 預先決定的結果（null → 回傳未完成 future，由測試手動完成）。 */
    private volatile ConfirmResult nextResult = ConfirmResult.CONFIRMED;
    private final AtomicInteger requestCount = new AtomicInteger();
    /** 最近一次發出的（未自動完成的）future，供測試手動完成。 */
    private volatile CompletableFuture<ConfirmResult> lastFuture;

    public record Alert(AlertLevel level, String message) {}
    public record LoginRequest(String playerName, UUID uuid, Duration ttl) {}

    public FakeNotifier available(boolean v) { this.available = v; return this; }
    public FakeNotifier nextResult(ConfirmResult r) { this.nextResult = r; return this; }

    @Override
    public void notice(String message) { notices.add(message); }

    @Override
    public void alert(AlertLevel level, String message) { alerts.add(new Alert(level, message)); }

    @Override
    public CompletableFuture<ConfirmResult> requestLoginConfirmation(String playerName, UUID playerUuid, Duration ttl) {
        requestCount.incrementAndGet();
        loginRequests.add(new LoginRequest(playerName, playerUuid, ttl));
        CompletableFuture<ConfirmResult> f = new CompletableFuture<>();
        lastFuture = f;
        if (nextResult != null) {
            f.complete(nextResult);
        }
        return f;
    }

    @Override
    public boolean isAvailable() { return available; }

    // ── 測試輔助 ─────────────────────────────────────────

    public int loginRequestCount() { return requestCount.get(); }

    /** 手動完成最近一個登入確認 future（用於 nextResult=null 時）。 */
    public void completeLast(ConfirmResult r) {
        CompletableFuture<ConfirmResult> f = lastFuture;
        if (f != null) f.complete(r);
    }

    public boolean hasAlert(AlertLevel level) {
        return alerts.stream().anyMatch(a -> a.level() == level);
    }

    public long alertCount(AlertLevel level) {
        return alerts.stream().filter(a -> a.level() == level).count();
    }
}
