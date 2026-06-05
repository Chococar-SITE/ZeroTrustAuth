package com.chococar.zerotrust.auth;

import com.chococar.zerotrust.audit.AuthMethod;

import java.time.Instant;
import java.util.UUID;

/**
 * 單一管理員的 Session 狀態（計劃 4.1）。<b>僅存記憶體</b>（計劃 4.3），伺服器重啟即清空。
 *
 * <p>狀態欄位可變（在狀態機之間轉移），故各欄位以 {@code volatile} 標記以利跨執行緒讀取。
 */
final class AdminSession {

    private final UUID uuid;
    private volatile SessionState state;
    private volatile String connectionId;
    private volatile AuthMethod method;
    private volatile Instant verifiedAt;
    private volatile Instant expiresAt;

    AdminSession(UUID uuid, SessionState state, String connectionId) {
        this.uuid = uuid;
        this.state = state;
        this.connectionId = connectionId;
    }

    UUID uuid() { return uuid; }
    SessionState state() { return state; }
    void state(SessionState s) { this.state = s; }
    String connectionId() { return connectionId; }
    void connectionId(String c) { this.connectionId = c; }
    AuthMethod method() { return method; }
    void method(AuthMethod m) { this.method = m; }
    Instant verifiedAt() { return verifiedAt; }
    void verifiedAt(Instant t) { this.verifiedAt = t; }
    Instant expiresAt() { return expiresAt; }
    void expiresAt(Instant t) { this.expiresAt = t; }
}
