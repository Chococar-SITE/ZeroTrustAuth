package com.chococar.zerotrust.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 信任裝置記憶（計劃 4.6）。選項 A 已驗證的裝置在登出後一段窗口內（預設 15 分鐘）
 * 重連時，可即時簽新 Nonce 確認、免完整流程。
 *
 * <ul>
 *   <li>以「帳號 + 公鑰指紋」識別裝置；<b>僅存記憶體</b>，重啟即失效。</li>
 *   <li><b>僅適用於選項 A 裝置</b>：純選項 B（無金鑰）不適用，因缺金鑰可即時證明身份。</li>
 *   <li>此機制不延長 Session TTL 本身。</li>
 * </ul>
 */
public final class TrustedDeviceCache {

    private final Duration window;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public TrustedDeviceCache(Duration window) {
        this.window = window;
    }

    /** 記錄某帳號最近一次以選項 A 成功驗證的裝置指紋，於 now + window 後失效。 */
    public void record(UUID uuid, String fingerprint, Instant now) {
        if (uuid == null || fingerprint == null) {
            return;
        }
        entries.put(uuid, new Entry(fingerprint, now.plus(window)));
    }

    /**
     * 取得該帳號目前仍在信任窗口內的裝置指紋（若已過期則順帶清除並回傳空）。
     */
    public Optional<String> trustedFingerprint(UUID uuid, Instant now) {
        Entry e = entries.get(uuid);
        if (e == null) {
            return Optional.empty();
        }
        if (!now.isBefore(e.expiresAt)) {
            entries.remove(uuid, e);
            return Optional.empty();
        }
        return Optional.of(e.fingerprint);
    }

    /** 清除指定帳號的信任裝置記憶。 */
    public void clear(UUID uuid) {
        if (uuid != null) {
            entries.remove(uuid);
        }
    }

    private static final class Entry {
        final String fingerprint;
        final Instant expiresAt;

        Entry(String fingerprint, Instant expiresAt) {
            this.fingerprint = fingerprint;
            this.expiresAt = expiresAt;
        }
    }
}
