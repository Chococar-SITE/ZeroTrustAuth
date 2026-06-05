package com.chococar.zerotrust.audit;

import com.chococar.zerotrust.support.InMemoryLogSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditLogTest {

    private final byte[] secret = "audit-ip-secret-abcdef0123456789".getBytes();
    private InMemoryLogSink sink;
    private AuditLog log;

    @BeforeEach
    void setUp() {
        sink = new InMemoryLogSink();
        log = new AuditLog(sink, secret);
    }

    @Test
    void emittedLineHasAllRequiredKeys() {
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        log.log(Instant.parse("2026-06-05T14:32:11Z"), uuid, "Steve",
                AuthMethod.SIGNATURE_A, AuthResult.SUCCESS, "203.0.113.5", "sess-1");
        assertEquals(1, sink.lines.size());
        Map<String, String> obj = parseFlatJson(sink.last());

        assertTrue(obj.containsKey("timestamp"));
        assertTrue(obj.containsKey("player_uuid"));
        assertTrue(obj.containsKey("player_name"));
        assertTrue(obj.containsKey("auth_method"));
        assertTrue(obj.containsKey("result"));
        assertTrue(obj.containsKey("ip_hmac"));
        assertTrue(obj.containsKey("session_id"));

        assertEquals("2026-06-05T14:32:11Z", obj.get("timestamp"));
        assertEquals(uuid.toString(), obj.get("player_uuid"));
        assertEquals("Steve", obj.get("player_name"));
        assertEquals("SIGNATURE_A", obj.get("auth_method"));
        assertEquals("SUCCESS", obj.get("result"));
        assertEquals("sess-1", obj.get("session_id"));
    }

    @Test
    void ipHmacPresentAndNotEqualToRawIp() {
        String ip = "203.0.113.5";
        log.log(Instant.now(), UUID.randomUUID(), "Steve",
                AuthMethod.SIGNATURE_A, AuthResult.SUCCESS, ip, "sess-1");
        Map<String, String> obj = parseFlatJson(sink.last());
        String ipHmac = obj.get("ip_hmac");
        assertFalse(ipHmac.isEmpty());
        assertNotEquals(ip, ipHmac);
        // 應與 IpHasher 結果一致（HMAC-SHA256，非裸 IP）。
        assertEquals(IpHasher.hmacSha256Hex(secret, ip), ipHmac);
    }

    @Test
    void ipHmacEmptyWhenIpNull() {
        log.log(Instant.now(), UUID.randomUUID(), "Steve",
                AuthMethod.OUT_OF_BAND_B, AuthResult.FAIL, null, "sess-1");
        Map<String, String> obj = parseFlatJson(sink.last());
        // ip 為 null 時 ip_hmac 應為空字串（不洩漏、不含原始 IP）。
        assertEquals("", obj.get("ip_hmac"));
    }

    @Test
    void secretIsNeverInOutput() {
        log.log(Instant.now(), UUID.randomUUID(), "Steve",
                AuthMethod.SIGNATURE_A, AuthResult.SUCCESS, "203.0.113.5", "sess-1");
        String line = sink.last();
        // 秘密（密鑰鹽）絕不可出現在日誌中。
        assertFalse(line.contains(new String(secret)));
    }

    @Test
    void escapesSpecialCharactersInName() {
        // 含引號 / 反斜線 / 換行的名稱必須正確跳脫，仍可解析為單一 JSON 物件。
        String tricky = "ev\"il\\name\nnewline";
        log.log(Instant.parse("2026-06-05T14:32:11Z"), UUID.randomUUID(), tricky,
                AuthMethod.SIGNATURE_A, AuthResult.SUCCESS, null, "s");
        Map<String, String> obj = parseFlatJson(sink.last());
        assertEquals(tricky, obj.get("player_name"));
    }

    @Test
    void singleLineNoRawNewline() {
        log.log(Instant.now(), UUID.randomUUID(), "line\nbreak",
                AuthMethod.SIGNATURE_A, AuthResult.SUCCESS, null, "s");
        // 輸出本身不得含原始換行（換行由 LogSink 負責）。
        assertFalse(sink.last().contains("\n"));
    }

    @Test
    void nullNameAndSessionBecomeEmpty() {
        log.log(Instant.parse("2026-06-05T14:32:11Z"), UUID.randomUUID(), null,
                AuthMethod.SIGNATURE_A, AuthResult.SUCCESS, null, null);
        Map<String, String> obj = parseFlatJson(sink.last());
        assertEquals("", obj.get("player_name"));
        assertEquals("", obj.get("session_id"));
    }

    // ── 極簡 JSON 解析器（僅支援 {"k":"v", ...} 扁平字串物件），用於驗證可解析性 ──
    private static Map<String, String> parseFlatJson(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        int n = json.length();
        expect(json.charAt(i++) == '{', "expected {");
        skipWs(json, i);
        if (json.charAt(skipWsIdx(json, i)) == '}') {
            return out;
        }
        while (i < n) {
            i = skipWsIdx(json, i);
            String key = readString(json, i);
            i = afterString(json, i);
            i = skipWsIdx(json, i);
            expect(json.charAt(i++) == ':', "expected :");
            i = skipWsIdx(json, i);
            String val = readString(json, i);
            i = afterString(json, i);
            out.put(key, val);
            i = skipWsIdx(json, i);
            char c = json.charAt(i++);
            if (c == '}') break;
            expect(c == ',', "expected , or }");
        }
        return out;
    }

    private static String readString(String s, int start) {
        expect(s.charAt(start) == '"', "expected opening quote at " + start);
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') break;
            if (c == '\\') {
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        String hex = s.substring(i, i + 4);
                        sb.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                    }
                    default -> throw new AssertionError("bad escape \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int afterString(String s, int start) {
        int i = start + 1;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return i;
            if (c == '\\') i++; // 跳過跳脫字元
        }
    }

    private static int skipWsIdx(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    private static void skipWs(String s, int i) { /* no-op helper retained for clarity */ }

    private static void expect(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }
}
