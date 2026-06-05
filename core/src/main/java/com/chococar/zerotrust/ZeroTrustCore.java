package com.chococar.zerotrust;

import com.chococar.zerotrust.audit.AuditLog;
import com.chococar.zerotrust.audit.AuthMethod;
import com.chococar.zerotrust.audit.AuthResult;
import com.chococar.zerotrust.auth.AuthEngine;
import com.chococar.zerotrust.auth.ChallengeManager;
import com.chococar.zerotrust.auth.CommandResult;
import com.chococar.zerotrust.auth.Ed25519Verifier;
import com.chococar.zerotrust.auth.EnrollmentManager;
import com.chococar.zerotrust.auth.PublicKeyStore;
import com.chococar.zerotrust.auth.SessionManager;
import com.chococar.zerotrust.auth.TrustedDeviceCache;
import com.chococar.zerotrust.config.ZeroTrustConfig;
import com.chococar.zerotrust.notify.AlertLevel;
import com.chococar.zerotrust.notify.ConfirmResult;
import com.chococar.zerotrust.notify.NotificationThrottler;
import com.chococar.zerotrust.platform.PlatformAdapter;
import com.chococar.zerotrust.platform.ScheduledTask;
import com.chococar.zerotrust.platform.Scheduler;
import com.chococar.zerotrust.platform.StoredKey;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 零信任驗證引擎核心編排（計劃 3.1 / 3.4 / 4 / 6）。實作 {@link AuthEngine}，
 * 接線 {@link PlatformAdapter}、{@link NotificationThrottler}、{@link Scheduler} 與各驗證元件。
 *
 * <p><b>Fail-Closed</b>（安全不變式 1）：任何不確定情況一律拒絕授權。
 * 安全模式下（{@link #enterSafeMode(String)}）所有管理員一律凍結、永不授權。
 *
 * <p>所有狀態以並行容器保存，可從任意執行緒安全存取；所有時間取自 {@link EngineContext#clock()}。
 */
public final class ZeroTrustCore implements AuthEngine {

    // ── 訊息常數（玩家可見；繁體中文）─────────────────────
    private static final String MSG_FROZEN_PROMPT = "§c請完成身份驗證以取得管理員權限";
    private static final String MSG_SUCCESS = "§a管理員身份驗證成功";
    private static final String MSG_KICK_TIMEOUT = "§c驗證逾時";
    private static final String MSG_KICK_NO_KEY = "§c未登記驗證金鑰，請先設定客戶端 Mod";
    private static final String MSG_KICK_TOO_MANY = "§c驗證失敗次數過多";
    private static final String MSG_KICK_CANNOT_VERIFY = "§c無法完成驗證，請稍後再試";
    private static final String MSG_KICK_REVOKED = "§c金鑰已被撤銷，請重新驗證";
    private static final String MSG_KICK_PACKET_FLOOD = "§c封包速率超限";
    private static final String MSG_CONSOLE_ONLY = "§c此指令僅限主控台執行";

    private final EngineContext ctx;
    private final ZeroTrustConfig config;
    private final PlatformAdapter adapter;
    private final Scheduler scheduler;
    private final Clock clock;
    private final String domain;

    private final Ed25519Verifier verifier;
    private final ChallengeManager challengeManager;
    private final PublicKeyStore keyStore;
    private final EnrollmentManager enrollmentManager;
    private final SessionManager sessionManager;
    private final TrustedDeviceCache trustedDeviceCache;
    private final NotificationThrottler throttler;
    private final AuditLog auditLog;

    // 失敗次數（選項 A 簽名重試）。
    private final Map<UUID, AtomicInteger> failures = new ConcurrentHashMap<>();
    // 選項 A 逾時任務（成功 / 解決時取消）。
    private final Map<UUID, ScheduledTask> pendingTimeouts = new ConcurrentHashMap<>();
    // 連線 ID（供逾時 / 帶外流程在事件之外引用）。
    private final Map<UUID, String> connectionIds = new ConcurrentHashMap<>();
    // 凍結期間封包速率：每帳號的「秒槽」與該秒計數。
    private final Map<UUID, PacketWindow> packetWindows = new ConcurrentHashMap<>();
    // 所有已排程任務（shutdown 時全數取消，fail-closed）。
    private final Set<ScheduledTask> allTasks = new CopyOnWriteArraySet<>();

    private volatile boolean safeMode = false;
    private volatile String safeModeReason = null;
    private final ScheduledTask cleanupTask;

    /** 平台層唯一接線點。 */
    public ZeroTrustCore(EngineContext ctx) {
        this.ctx = ctx;
        this.config = ctx.config();
        this.adapter = ctx.adapter();
        this.scheduler = ctx.scheduler();
        this.clock = ctx.clock();
        this.domain = config.signatureDomain();

        this.verifier = new Ed25519Verifier();
        this.challengeManager = new ChallengeManager(clock);
        this.keyStore = new PublicKeyStore(ctx.keyRepository(), verifier, clock);
        this.enrollmentManager =
                new EnrollmentManager(clock, config.enrollmentTokenTtl(), config.enrollmentMaxAttempts());
        this.sessionManager = new SessionManager(clock, config.sessionTtl());
        this.trustedDeviceCache = new TrustedDeviceCache(config.trustedDeviceWindow());
        this.throttler = new NotificationThrottler(ctx.notifier(), config.notifyCooldown());
        this.auditLog = new AuditLog(ctx.logSink(), ctx.ipHmacSecret());

        // 週期性清理：Session 到期撤權 + 過期 Nonce/Token 清除（計劃 4 / 6）。
        Duration period = Duration.ofSeconds(1);
        this.cleanupTask = scheduler.scheduleRepeating(period, period, this::runCleanup);
        allTasks.add(cleanupTask);
    }

    // ── 安全模式（fail-closed）────────────────────────────

    /** 進入安全模式：凍結中的管理員一律不授權，所有驗證嘗試被拒（計劃 6.3）。 */
    public void enterSafeMode(String reason) {
        this.safeMode = true;
        this.safeModeReason = reason;
        ctx.notifier().alert(AlertLevel.EMERGENCY, "進入安全模式：" + reason + "（拒絕所有管理員授權）");
    }

    public boolean isSafeMode() {
        return safeMode;
    }

    // ── 連線生命週期 ─────────────────────────────────────

    @Override
    public void onAdminJoin(UUID uuid, String playerName, String connectionId) {
        connectionIds.put(uuid, connectionId == null ? "" : connectionId);
        // 安全模式：凍結並保持，永不授權（fail-closed）。
        if (safeMode) {
            sessionManager.freeze(uuid, connectionId);
            adapter.freezePlayer(uuid);
            adapter.sendMessage(uuid, MSG_FROZEN_PROMPT);
            ctx.notifier().notice("安全模式中，管理員 " + safeName(playerName) + " 被凍結且拒絕授權（"
                    + safeModeReason + "）");
            return;
        }
        sessionManager.freeze(uuid, connectionId);
        adapter.freezePlayer(uuid);
        if (config.stripVanillaOp()) {
            adapter.stripVanillaOp(uuid);
        }
        adapter.sendMessage(uuid, MSG_FROZEN_PROMPT);
        startAuth(uuid, playerName, connectionId);
    }

    @Override
    public void onAdminQuit(UUID uuid) {
        // 登出立即撤權（不等 TTL）；選項 A 已驗證者已於成功時記入信任窗口（計劃 4.3 / 4.6）。
        adapter.revokeAdminPerm(uuid);
        if (config.stripVanillaOp()) {
            adapter.stripVanillaOp(uuid);
        }
        cancelTimeout(uuid);
        challengeManager.clear(uuid);
        failures.remove(uuid);
        packetWindows.remove(uuid);
        connectionIds.remove(uuid);
        sessionManager.quit(uuid);
    }

    private void startAuth(UUID uuid, String playerName, String connectionId) {
        if (safeMode) {
            // 縱深防禦：安全模式下絕不啟動任何授權路徑。
            return;
        }
        if (keyStore.hasKeys(uuid)) {
            // 選項 A：送 Nonce，等待簽名；逾時依 allow_fallback 決定降級或踢出。
            byte[] nonce = challengeManager.issue(uuid, connectionId);
            adapter.sendChallenge(uuid, nonce);
            scheduleOptionATimeout(uuid, playerName, connectionId);
        } else if (config.allowFallback()) {
            // 無公鑰但允許降級：走選項 B。
            fallbackToDiscord(uuid, playerName);
        } else {
            // 嚴格模式：無公鑰即拒絕。
            audit(uuid, playerName, AuthMethod.SIGNATURE_A, AuthResult.FALLBACK_DENIED, connectionId);
            adapter.kickPlayer(uuid, MSG_KICK_NO_KEY);
        }
    }

    private void scheduleOptionATimeout(UUID uuid, String playerName, String connectionId) {
        cancelTimeout(uuid);
        ScheduledTask task = scheduler.scheduleOnce(config.optionATimeout(), () -> {
            pendingTimeouts.remove(uuid);
            // 仍凍結且尚未驗證才處理逾時。
            if (!sessionManager.isFrozen(uuid)) {
                return;
            }
            if (config.allowFallback()) {
                audit(uuid, playerName, AuthMethod.SIGNATURE_A, AuthResult.DOWNGRADED_A_TO_B, connectionId);
                fallbackToDiscord(uuid, playerName);
            } else {
                audit(uuid, playerName, AuthMethod.SIGNATURE_A, AuthResult.FALLBACK_DENIED, connectionId);
                adapter.kickPlayer(uuid, MSG_KICK_TIMEOUT);
                throttler.notice("管理員 " + safeName(playerName) + " 驗證逾時（已停用降級）");
            }
        });
        pendingTimeouts.put(uuid, task);
        allTasks.add(task);
    }

    // ── 選項 A：簽名回應 ─────────────────────────────────

    @Override
    public void onSignatureResponse(UUID uuid, String connectionId, byte[] nonce, byte[] signature) {
        String name = playerName(uuid);
        if (safeMode) {
            // 安全模式：拒絕所有驗證。
            recordFailure(uuid, name);
            return;
        }
        // Nonce 必須存在、相符、綁定此連線、未過期、未使用過。
        if (!challengeManager.consume(uuid, connectionId, nonce)) {
            recordFailure(uuid, name);
            return;
        }
        Optional<String> fp = keyStore.verifyAgainstAnyKey(uuid, domain, nonce, signature);
        if (fp.isEmpty()) {
            recordFailure(uuid, name);
            return;
        }
        // 通過：取消逾時、轉 VERIFIED、解凍、授權、記入信任裝置。
        cancelTimeout(uuid);
        String fingerprint = fp.get();
        Instant now = clock.instant();
        // 若此次重連匹配信任窗口內的同一指紋，記為 TRUSTED_RESUME。
        Optional<String> trusted = trustedDeviceCache.trustedFingerprint(uuid, now);
        AuthResult result = trusted.isPresent() && trusted.get().equals(fingerprint)
                ? AuthResult.TRUSTED_RESUME
                : AuthResult.SUCCESS;

        sessionManager.verify(uuid, AuthMethod.SIGNATURE_A);
        adapter.unfreezePlayer(uuid);
        adapter.grantAdminPerm(uuid);
        trustedDeviceCache.record(uuid, fingerprint, now);
        failures.remove(uuid);
        throttler.reset(uuid);
        audit(uuid, name, AuthMethod.SIGNATURE_A, result, connectionId);
        adapter.sendMessage(uuid, MSG_SUCCESS);
    }

    private void recordFailure(UUID uuid, String playerName) {
        int count = failures.computeIfAbsent(uuid, u -> new AtomicInteger()).incrementAndGet();
        audit(uuid, playerName, AuthMethod.SIGNATURE_A, AuthResult.FAIL, connectionIds.get(uuid));
        if (count >= config.maxAttempts()) {
            adapter.kickPlayer(uuid, MSG_KICK_TOO_MANY);
            throttler.alert(AlertLevel.HIGH,
                    "管理員 " + safeName(playerName) + " 驗證失敗達上限（" + count + " 次），已踢出");
            failures.remove(uuid);
            cancelTimeout(uuid);
        } else {
            int remaining = config.maxAttempts() - count;
            adapter.sendMessage(uuid, "§c驗證失敗，剩餘 " + remaining + " 次");
        }
    }

    // ── 選項 B：Discord 帶外驗證 ─────────────────────────

    private void fallbackToDiscord(UUID uuid, String playerName) {
        if (!throttler.isAvailable()) {
            // 選項 B 不可用：無法驗證 → fail-closed 踢出。
            throttler.notice("選項 B 不可用，無法驗證管理員 " + safeName(playerName) + "（fail-closed 踢出）");
            adapter.kickPlayer(uuid, MSG_KICK_CANNOT_VERIFY);
            return;
        }
        Instant now = clock.instant();
        if (!throttler.allowLoginRequest(uuid, now)) {
            // 冷卻中：合併計數，不重複發 DM（計劃 6.7）。已有先前請求在進行中。
            return;
        }
        String connectionId = connectionIds.get(uuid);
        throttler.requestLoginConfirmation(playerName, uuid, config.optionBTokenTtl())
                .whenComplete((res, err) -> {
                    if (err != null || res == null) {
                        // 例外視為發送失敗 → fail-closed。
                        onOutOfBandSendFailed(uuid, playerName);
                        return;
                    }
                    switch (res) {
                        case CONFIRMED -> {
                            if (safeMode || !adapter.isOnline(uuid)) {
                                return;
                            }
                            sessionManager.verify(uuid, AuthMethod.OUT_OF_BAND_B);
                            adapter.unfreezePlayer(uuid);
                            adapter.grantAdminPerm(uuid);
                            failures.remove(uuid);
                            throttler.reset(uuid);
                            audit(uuid, playerName, AuthMethod.OUT_OF_BAND_B, AuthResult.SUCCESS, connectionId);
                            adapter.sendMessage(uuid, MSG_SUCCESS);
                        }
                        case DENIED -> {
                            throttler.alert(AlertLevel.EMERGENCY,
                                    "管理員 " + safeName(playerName) + " 點擊『不是我』，建議立即修改密碼");
                            audit(uuid, playerName, AuthMethod.OUT_OF_BAND_B, AuthResult.FAIL, connectionId);
                            adapter.kickPlayer(uuid, MSG_KICK_CANNOT_VERIFY);
                        }
                        case TIMEOUT -> {
                            audit(uuid, playerName, AuthMethod.OUT_OF_BAND_B, AuthResult.FAIL, connectionId);
                            adapter.kickPlayer(uuid, MSG_KICK_TIMEOUT);
                        }
                        case SEND_FAILED -> onOutOfBandSendFailed(uuid, playerName);
                    }
                });
    }

    private void onOutOfBandSendFailed(UUID uuid, String playerName) {
        // DM 發送失敗：選項 B 靜默失效 → fail-closed 踢出並回報（計劃 3.3）。
        throttler.notice("選項 B DM 發送失敗（管理員 " + safeName(playerName) + "），fail-closed 踢出");
        adapter.kickPlayer(uuid, MSG_KICK_CANNOT_VERIFY);
    }

    // ── 凍結期間封包速率限制（計劃 6.4）─────────────────

    @Override
    public boolean onFrozenPacket(UUID uuid) {
        if (!sessionManager.isFrozen(uuid)) {
            return false;
        }
        long second = clock.instant().getEpochSecond();
        PacketWindow w = packetWindows.computeIfAbsent(uuid, u -> new PacketWindow());
        int count = w.increment(second);
        if (count > config.freezePacketLimitPerSecond()) {
            adapter.kickPlayer(uuid, MSG_KICK_PACKET_FLOOD);
            throttler.alert(AlertLevel.HIGH,
                    "管理員 " + safeName(playerName(uuid)) + " 凍結期間封包速率超限（疑似主動攻擊）");
        }
        // 凍結期間一律攔截非驗證封包。
        return true;
    }

    // ── /authkey 指令（計劃 3.7）─────────────────────────

    @Override
    public CommandResult enroll(UUID target, boolean fromConsole) {
        if (!fromConsole) {
            return CommandResult.fail(MSG_CONSOLE_ONLY);
        }
        String token = enrollmentManager.issue(target);
        throttler.notice("金鑰註冊請求 " + target);
        long minutes = config.enrollmentTokenTtl().toMinutes();
        // 整合標記：訊息必含 "Enrollment code for <uuid>: <token>"。
        return CommandResult.ok("Enrollment code for " + target + ": " + token
                + "（" + minutes + " 分鐘有效，僅主控台可見）");
    }

    @Override
    public CommandResult upload(UUID self, String base64PublicKey, String enrollmentCode, String label) {
        EnrollmentManager.Result r = enrollmentManager.redeem(self, enrollmentCode);
        switch (r) {
            case SUCCESS:
                try {
                    keyStore.addKey(self, base64PublicKey, "generated", label == null ? "default" : label);
                } catch (IllegalArgumentException e) {
                    return CommandResult.fail("§c公鑰格式錯誤，僅接受 Ed25519");
                }
                throttler.notice("管理員 " + self + " 已上傳新公鑰（label="
                        + (label == null ? "default" : label) + "）");
                return CommandResult.ok("§a公鑰已登記");
            case NO_TOKEN:
            case INVALID:
                return CommandResult.fail("§c註冊碼錯誤");
            case EXPIRED:
                return CommandResult.fail("§c註冊碼已過期");
            case LOCKED:
                throttler.alert(AlertLevel.HIGH, "管理員 " + self + " 註冊碼錯誤次數過多，已鎖定");
                return CommandResult.fail("§c嘗試過多，已鎖定");
            default:
                return CommandResult.fail("§c註冊碼錯誤");
        }
    }

    @Override
    public CommandResult rotate(UUID self, String base64NewPublicKey, String label) {
        // 換鑰須先以「既有金鑰（選項 A）」證明身份（計劃 3.5）。
        if (!(sessionManager.isVerified(self) && sessionManager.getMethod(self) == AuthMethod.SIGNATURE_A)) {
            return CommandResult.fail("§c換鑰需先以金鑰驗證身份");
        }
        try {
            keyStore.addKey(self, base64NewPublicKey, "generated", label == null ? "default" : label);
        } catch (IllegalArgumentException e) {
            return CommandResult.fail("§c公鑰格式錯誤，僅接受 Ed25519");
        }
        throttler.notice("管理員 " + self + " 已換鑰（label=" + (label == null ? "default" : label) + "）");
        return CommandResult.ok("§a已換鑰");
    }

    @Override
    public CommandResult verify(UUID self, String connectionId) {
        // 在線重新驗證：重新凍結並再走驗證流程（計劃 4.7）。
        connectionIds.put(self, connectionId == null ? "" : connectionId);
        sessionManager.freeze(self, connectionId);
        adapter.freezePlayer(self);
        if (config.stripVanillaOp()) {
            adapter.stripVanillaOp(self);
        }
        String name = adapter.getPlayerName(self).orElse("");
        startAuth(self, name, connectionId);
        return CommandResult.ok("§b已開始重新驗證");
    }

    @Override
    public CommandResult list(UUID self) {
        List<StoredKey> keys = keyStore.getStoredKeys(self);
        if (keys.isEmpty()) {
            return CommandResult.ok("（無已登記金鑰）");
        }
        StringBuilder sb = new StringBuilder("§e已登記金鑰：");
        for (StoredKey k : keys) {
            sb.append('\n')
              .append("§7- §f").append(k.label())
              .append(" §7(source=").append(k.source())
              .append(", last_used=").append(k.lastUsed() == null ? "從未" : k.lastUsed().toString())
              .append(')');
        }
        return CommandResult.ok(sb.toString());
    }

    @Override
    public CommandResult revoke(UUID target, String label, boolean fromConsole) {
        if (!fromConsole) {
            return CommandResult.fail(MSG_CONSOLE_ONLY);
        }
        if (label == null) {
            keyStore.removeAll(target);
        } else {
            keyStore.removeKey(target, label);
        }
        // 撤銷須立即終止活躍 Session（計劃 6.5）。
        if (sessionManager.isVerified(target)) {
            adapter.revokeAdminPerm(target);
            sessionManager.revoke(target);
            adapter.kickPlayer(target, MSG_KICK_REVOKED);
        }
        throttler.alert(AlertLevel.EMERGENCY, "金鑰撤銷 " + target
                + (label == null ? "（全部）" : "（label=" + label + "）"));
        return CommandResult.ok("§a已撤銷");
    }

    // ── 狀態查詢 / 生命週期 ───────────────────────────────

    @Override
    public boolean isFrozen(UUID uuid) {
        return sessionManager.isFrozen(uuid);
    }

    @Override
    public boolean isVerified(UUID uuid) {
        return sessionManager.isVerified(uuid);
    }

    @Override
    public void shutdown() {
        // fail-closed 清理（計劃 5.2）：撤回所有已驗證管理員權限、清空 Session、取消任務、關閉日誌。
        for (UUID uuid : sessionManager.getAllVerified()) {
            adapter.revokeAdminPerm(uuid);
        }
        sessionManager.clearAll();
        for (ScheduledTask t : allTasks) {
            t.cancel();
        }
        allTasks.clear();
        pendingTimeouts.clear();
        ctx.logSink().close();
    }

    // ── 內部 ─────────────────────────────────────────────

    /** 週期性清理：Session 到期撤權 + 過期 Nonce/Token 清除。 */
    private void runCleanup() {
        Instant now = clock.instant();
        List<UUID> expired = sessionManager.tickExpire(now);
        for (UUID uuid : expired) {
            // Session 到期：撤回權限、剝奪原版 OP、通知玩家（計劃 4.1 / 4.7）。
            adapter.revokeAdminPerm(uuid);
            if (config.stripVanillaOp()) {
                adapter.stripVanillaOp(uuid);
            }
            if (adapter.isOnline(uuid)) {
                adapter.sendMessage(uuid, "§eSession 已到期，請以 /authkey verify 重新驗證");
            }
            audit(uuid, playerName(uuid), sessionManager.getMethod(uuid), AuthResult.FAIL,
                    connectionIds.get(uuid));
        }
        challengeManager.purgeExpired(now);
        enrollmentManager.purgeExpired(now);
    }

    private void cancelTimeout(UUID uuid) {
        ScheduledTask t = pendingTimeouts.remove(uuid);
        if (t != null) {
            t.cancel();
            allTasks.remove(t);
        }
    }

    private void audit(UUID uuid, String name, AuthMethod method, AuthResult result, String sessionId) {
        // IP 不在核心契約中（adapter 未暴露）；ip=null 則省略 ip_hmac 欄位（資料最小化）。
        auditLog.log(clock.instant(), uuid, name, method, result, null, sessionId);
    }

    private String playerName(UUID uuid) {
        return adapter.getPlayerName(uuid).orElse("");
    }

    private static String safeName(String name) {
        return name == null || name.isBlank() ? "(unknown)" : name;
    }

    /** 每帳號的「秒槽 + 該秒計數」，用於凍結期間封包速率限制。 */
    private static final class PacketWindow {
        private long second = Long.MIN_VALUE;
        private int count;

        synchronized int increment(long currentSecond) {
            if (currentSecond != second) {
                second = currentSecond;
                count = 0;
            }
            return ++count;
        }
    }
}
