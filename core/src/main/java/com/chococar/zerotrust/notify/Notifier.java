package com.chococar.zerotrust.notify;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 通知與帶外驗證的傳輸抽象。具體實作（{@code DiscordNotifier}，JDA）位於平台層，
 * 核心僅依賴此介面，因此選項 B 的編排邏輯（{@code OutOfBandChallenge}）可用假實作測試。
 *
 * <p>實作須處理 DM 失敗退回後備頻道（計劃 3.3），並回報 {@link ConfirmResult#SEND_FAILED}。
 */
public interface Notifier {

    /** 低優先資訊（記錄至 Discord 頻道 / 主控台）。 */
    void notice(String message);

    /** 警報；{@link AlertLevel#EMERGENCY} 不受冷卻限制，一律即時送出（計劃 6.7）。 */
    void alert(AlertLevel level, String message);

    /**
     * 向管理員發出登入確認請求（選項 B），於 {@code ttl} 後逾時。
     *
     * @return 完成於 {@link ConfirmResult}；實作不得阻塞呼叫端。
     */
    CompletableFuture<ConfirmResult> requestLoginConfirmation(String playerName, UUID playerUuid, Duration ttl);

    /**
     * 傳輸通道是否可用（如 Discord 連線正常）。供啟動自檢與選項 B 可用性判斷。
     * 不可用時選項 B 應視為停用（計劃 6.3）。
     */
    boolean isAvailable();
}
