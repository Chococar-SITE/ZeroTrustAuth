package com.chococar.zerotrust.platform;

import java.time.Instant;

/**
 * 持久化的公鑰紀錄（計劃 3.6 多金鑰）。
 *
 * @param label          裝置標籤（如 {@code desktop}、{@code laptop}）
 * @param publicKeyBase64 X.509/SPKI 編碼的 Ed25519 公鑰（Base64）
 * @param source         來源，{@code "generated"} 或 {@code "ssh"}（供審計）
 * @param lastUsed       最後成功使用時間；可為 {@code null}
 */
public record StoredKey(String label, String publicKeyBase64, String source, Instant lastUsed) {
    public StoredKey withLastUsed(Instant ts) {
        return new StoredKey(label, publicKeyBase64, source, ts);
    }
}
