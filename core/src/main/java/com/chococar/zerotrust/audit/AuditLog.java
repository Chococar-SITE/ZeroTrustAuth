package com.chococar.zerotrust.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 結構化審計日誌（計劃 6.2 / 安全不變式 9）。
 *
 * <p>每次驗證事件輸出<b>單行 JSON</b>，欄位精確為：
 * {@code timestamp, player_uuid, player_name, auth_method, result, ip_hmac, session_id}。
 * JSON 手工建構並正確跳脫（核心不得依賴外部函式庫）。
 *
 * <ul>
 *   <li>IP 一律以 {@link IpHasher}（HMAC-SHA256 + 密鑰鹽）雜湊為 {@code ip_hmac}；
 *       {@code ip} 為 {@code null} 時省略 {@code ip_hmac} 欄位。</li>
 *   <li><b>絕不</b>記錄任何秘密（Bot Token、HMAC 密鑰鹽本身等）。</li>
 *   <li>時間以 ISO-8601 UTC（{@link Instant#toString()}）輸出。</li>
 * </ul>
 */
public final class AuditLog {

    private final LogSink sink;
    private final byte[] ipHmacSecret;

    public AuditLog(LogSink sink, byte[] ipHmacSecret) {
        this.sink = Objects.requireNonNull(sink, "sink");
        // 防禦性複製：避免外部修改密鑰鹽。
        this.ipHmacSecret = Objects.requireNonNull(ipHmacSecret, "ipHmacSecret").clone();
    }

    /**
     * 記錄一次驗證事件。
     *
     * @param ts        事件時間（UTC）
     * @param uuid      玩家 UUID
     * @param name      玩家名稱（可為 {@code null} → 空字串）
     * @param method    驗證方式
     * @param result    驗證結果
     * @param ip        原始 IP（可為 {@code null}；非 null 時以 HMAC 雜湊後寫入 {@code ip_hmac}）
     * @param sessionId Session / 連線識別（可為 {@code null} → 空字串）
     */
    public void log(Instant ts, UUID uuid, String name, AuthMethod method, AuthResult result,
                    String ip, String sessionId) {
        StringBuilder sb = new StringBuilder(192);
        sb.append('{');
        appendString(sb, "timestamp", ts == null ? "" : ts.toString());
        sb.append(',');
        appendString(sb, "player_uuid", uuid == null ? "" : uuid.toString());
        sb.append(',');
        appendString(sb, "player_name", name == null ? "" : name);
        sb.append(',');
        appendString(sb, "auth_method", method == null ? "" : method.name());
        sb.append(',');
        appendString(sb, "result", result == null ? "" : result.name());
        sb.append(',');
        // ip 為 null 即省略 ip_hmac（資料最小化）；否則 HMAC-SHA256 雜湊。
        String ipHmac = ip == null ? "" : IpHasher.hmacSha256Hex(ipHmacSecret, ip);
        appendString(sb, "ip_hmac", ipHmac);
        sb.append(',');
        appendString(sb, "session_id", sessionId == null ? "" : sessionId);
        sb.append('}');
        sink.write(sb.toString());
    }

    private static void appendString(StringBuilder sb, String key, String value) {
        sb.append('"');
        escape(sb, key);
        sb.append('"').append(':').append('"');
        escape(sb, value);
        sb.append('"');
    }

    /** 依 RFC 8259 跳脫 JSON 字串內容。 */
    private static void escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
    }
}
