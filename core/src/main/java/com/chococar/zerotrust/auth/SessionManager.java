package com.chococar.zerotrust.auth;

import com.chococar.zerotrust.audit.AuthMethod;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理員 Session 狀態機管理（計劃 4.1 / 4.3）。
 *
 * <pre>
 *   FROZEN ──verify──▶ VERIFIED ──TTL 到期/登出──▶ EXPIRED / REVOKED
 * </pre>
 *
 * <ul>
 *   <li><b>僅存記憶體</b>：重啟清空，不持久化。</li>
 *   <li>{@code verify} 記錄驗證方式與到期時間（now + sessionTtl）。</li>
 *   <li>{@code tickExpire} 將逾時的 VERIFIED Session 轉為 EXPIRED 並回傳其 UUID 清單，
 *       供引擎撤回權限。</li>
 * </ul>
 *
 * <p>狀態以 {@link ConcurrentHashMap} 保存，可從任意執行緒安全存取。
 */
public final class SessionManager {

    private final Clock clock;
    private final Duration sessionTtl;
    private final Map<UUID, AdminSession> sessions = new ConcurrentHashMap<>();

    public SessionManager(Clock clock, Duration sessionTtl) {
        this.clock = clock;
        this.sessionTtl = sessionTtl;
    }

    /** 進入凍結狀態，記錄該次連線 ID（重新驗證時覆寫舊 Session）。 */
    public void freeze(UUID uuid, String connectionId) {
        AdminSession s = sessions.computeIfAbsent(uuid,
                u -> new AdminSession(u, SessionState.FROZEN, connectionId));
        s.state(SessionState.FROZEN);
        s.connectionId(connectionId);
        s.method(null);
        s.verifiedAt(null);
        s.expiresAt(null);
    }

    /** 驗證通過：轉為 VERIFIED，記錄方式、驗證時間與到期時間（now + TTL）。 */
    public void verify(UUID uuid, AuthMethod method) {
        AdminSession s = sessions.computeIfAbsent(uuid,
                u -> new AdminSession(u, SessionState.FROZEN, null));
        Instant now = clock.instant();
        s.state(SessionState.VERIFIED);
        s.method(method);
        s.verifiedAt(now);
        s.expiresAt(now.plus(sessionTtl));
    }

    public boolean isFrozen(UUID uuid) {
        AdminSession s = sessions.get(uuid);
        return s != null && s.state() == SessionState.FROZEN;
    }

    public boolean isVerified(UUID uuid) {
        AdminSession s = sessions.get(uuid);
        return s != null && s.state() == SessionState.VERIFIED;
    }

    public SessionState getState(UUID uuid) {
        AdminSession s = sessions.get(uuid);
        return s == null ? null : s.state();
    }

    public AuthMethod getMethod(UUID uuid) {
        AdminSession s = sessions.get(uuid);
        return s == null ? null : s.method();
    }

    public String getConnectionId(UUID uuid) {
        AdminSession s = sessions.get(uuid);
        return s == null ? null : s.connectionId();
    }

    /**
     * 將所有已逾期（now >= expiresAt）的 VERIFIED Session 轉為 EXPIRED。
     *
     * @return 本次因逾期而被轉為 EXPIRED 的 UUID 清單（供引擎撤回權限）。
     */
    public List<UUID> tickExpire(Instant now) {
        List<UUID> expired = new ArrayList<>();
        for (AdminSession s : sessions.values()) {
            if (s.state() == SessionState.VERIFIED) {
                Instant exp = s.expiresAt();
                if (exp != null && !now.isBefore(exp)) {
                    s.state(SessionState.EXPIRED);
                    expired.add(s.uuid());
                }
            }
        }
        return expired;
    }

    /** 強制撤銷：轉為 REVOKED（保留紀錄，下次登入須重新驗證）。 */
    public void revoke(UUID uuid) {
        AdminSession s = sessions.get(uuid);
        if (s != null) {
            s.state(SessionState.REVOKED);
        }
    }

    /** 登出：移除 Session（記憶體釋放）。 */
    public void quit(UUID uuid) {
        sessions.remove(uuid);
    }

    /** 目前所有 VERIFIED 的管理員 UUID。 */
    public Set<UUID> getAllVerified() {
        Set<UUID> out = new HashSet<>();
        for (AdminSession s : sessions.values()) {
            if (s.state() == SessionState.VERIFIED) {
                out.add(s.uuid());
            }
        }
        return out;
    }

    /** 清空所有 Session（{@code onDisable} fail-closed 清理，計劃 5.2）。 */
    public void clearAll() {
        sessions.clear();
    }
}
