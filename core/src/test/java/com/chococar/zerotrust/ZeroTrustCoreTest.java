package com.chococar.zerotrust;

import com.chococar.zerotrust.auth.CommandResult;
import com.chococar.zerotrust.notify.AlertLevel;
import com.chococar.zerotrust.notify.ConfirmResult;
import com.chococar.zerotrust.support.CryptoTestKit;
import com.chococar.zerotrust.support.TestHarness;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZeroTrustCoreTest {

    private static final String DOMAIN = "MC-ZEROTRUST-AUTH-v1:";
    private static final String CONN = "conn-1";

    private UUID admin() { return UUID.randomUUID(); }

    private TestHarness withKey(TestHarness h, UUID uuid, KeyPair kp, String label) {
        return h.seedKey(uuid, label, CryptoTestKit.encodePublicKeyBase64(kp.getPublic()));
    }

    // (a) join with registered key → freeze + sendChallenge; valid signature → unfreeze+grant + SUCCESS audited.
    @Test
    void optionA_joinAndValidSignatureGrants() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness();
        withKey(h, uuid, kp, "desktop");
        ZeroTrustCore core = h.core();

        core.onAdminJoin(uuid, "Steve", CONN);
        assertTrue(h.adapter.wasFrozen(uuid));
        assertTrue(h.adapter.wasChallenged(uuid), "選項 A 應送出挑戰");
        assertTrue(h.adapter.strippedOp.contains(uuid), "登入應剝奪原版 OP");

        byte[] nonce = h.adapter.lastChallengeNonce(uuid);
        assertNotNull(nonce);
        byte[] sig = CryptoTestKit.sign(kp.getPrivate(), DOMAIN, nonce);
        core.onSignatureResponse(uuid, CONN, nonce, sig);

        assertTrue(h.adapter.wasUnfrozen(uuid));
        assertTrue(h.adapter.wasGranted(uuid));
        assertTrue(core.isVerified(uuid));
        assertTrue(h.logSink.lines.stream().anyMatch(l -> l.contains("\"result\":\"SUCCESS\"")
                && l.contains("\"auth_method\":\"SIGNATURE_A\"")));
    }

    // (b) 3 invalid signature responses → kick + HIGH alert.
    @Test
    void optionA_threeInvalidSignaturesKick() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness();
        withKey(h, uuid, kp, "desktop");
        ZeroTrustCore core = h.core();
        h.adapter.online(uuid, "Steve");

        core.onAdminJoin(uuid, "Steve", CONN);
        byte[] nonce = h.adapter.lastChallengeNonce(uuid);
        byte[] badSig = new byte[64];

        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                core.verify(uuid, CONN);
                nonce = h.adapter.lastChallengeNonce(uuid);
            }
            core.onSignatureResponse(uuid, CONN, nonce, badSig);
        }
        assertTrue(h.adapter.wasKicked(uuid), "失敗達上限應踢出");
        assertTrue(h.notifier.hasAlert(AlertLevel.HIGH), "應發出 HIGH 警報");
        assertFalse(core.isVerified(uuid));
    }

    // (c) no keys + allowFallback=true → requestLoginConfirmation; CONFIRMED→grant; DENIED→EMERGENCY+kick.
    @Test
    void optionB_confirmedGrants() {
        UUID uuid = admin();
        TestHarness h = new TestHarness(b -> b.allowFallback(true));
        h.adapter.online(uuid, "Steve");
        h.notifier.nextResult(ConfirmResult.CONFIRMED);

        h.core().onAdminJoin(uuid, "Steve", CONN);
        assertEquals(1, h.notifier.loginRequestCount(), "無金鑰應走選項 B");
        assertTrue(h.adapter.wasGranted(uuid));
        assertTrue(h.core().isVerified(uuid));
        assertTrue(h.logSink.lines.stream().anyMatch(l -> l.contains("\"auth_method\":\"OUT_OF_BAND_B\"")
                && l.contains("\"result\":\"SUCCESS\"")));
    }

    @Test
    void optionB_deniedEmergencyAndKick() {
        UUID uuid = admin();
        TestHarness h = new TestHarness(b -> b.allowFallback(true));
        h.adapter.online(uuid, "Steve");
        h.notifier.nextResult(ConfirmResult.DENIED);

        h.core().onAdminJoin(uuid, "Steve", CONN);
        assertTrue(h.notifier.hasAlert(AlertLevel.EMERGENCY), "『不是我』應觸發緊急警報");
        assertTrue(h.adapter.wasKicked(uuid));
        assertFalse(h.adapter.wasGranted(uuid));
    }

    @Test
    void optionB_timeoutKicks() {
        UUID uuid = admin();
        TestHarness h = new TestHarness(b -> b.allowFallback(true));
        h.adapter.online(uuid, "Steve");
        h.notifier.nextResult(ConfirmResult.TIMEOUT);

        h.core().onAdminJoin(uuid, "Steve", CONN);
        assertTrue(h.adapter.wasKicked(uuid));
        assertFalse(h.adapter.wasGranted(uuid));
    }

    @Test
    void optionB_sendFailedFailsClosed() {
        UUID uuid = admin();
        TestHarness h = new TestHarness(b -> b.allowFallback(true));
        h.adapter.online(uuid, "Steve");
        h.notifier.nextResult(ConfirmResult.SEND_FAILED);

        h.core().onAdminJoin(uuid, "Steve", CONN);
        assertTrue(h.adapter.wasKicked(uuid));
        assertFalse(h.adapter.wasGranted(uuid));
    }

    @Test
    void optionB_unavailableNotifierFailsClosed() {
        UUID uuid = admin();
        TestHarness h = new TestHarness(b -> b.allowFallback(true));
        h.adapter.online(uuid, "Steve");
        h.notifier.available(false);

        h.core().onAdminJoin(uuid, "Steve", CONN);
        assertEquals(0, h.notifier.loginRequestCount(), "Notifier 不可用時不應發出請求");
        assertTrue(h.adapter.wasKicked(uuid), "選項 B 不可用 → fail-closed 踢出");
        assertFalse(h.adapter.wasGranted(uuid));
    }

    // (d) no keys + allowFallback=false → kick (strict), no grant.
    @Test
    void strictMode_noKeysKicks() {
        UUID uuid = admin();
        TestHarness h = new TestHarness(b -> b.allowFallback(false));
        h.adapter.online(uuid, "Steve");

        h.core().onAdminJoin(uuid, "Steve", CONN);
        assertTrue(h.adapter.wasKicked(uuid));
        assertFalse(h.adapter.wasGranted(uuid));
        assertEquals(0, h.notifier.loginRequestCount(), "嚴格模式不應走選項 B");
        assertTrue(h.logSink.lines.stream().anyMatch(l -> l.contains("\"result\":\"FALLBACK_DENIED\"")));
    }

    // (e) Option A timeout fired: allowFallback=true → Discord + DOWNGRADED_A_TO_B; false → kick + FALLBACK_DENIED.
    @Test
    void optionATimeout_downgradesToDiscordWhenFallbackAllowed() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness(b -> b.allowFallback(true));
        h.adapter.online(uuid, "Steve");
        withKey(h, uuid, kp, "desktop");
        ZeroTrustCore core = h.core();
        h.notifier.nextResult(ConfirmResult.CONFIRMED);

        core.onAdminJoin(uuid, "Steve", CONN);
        assertTrue(h.adapter.wasChallenged(uuid));
        h.scheduler.fireOnce(); // 觸發逾時。
        assertEquals(1, h.notifier.loginRequestCount(), "逾時後應降級至選項 B");
        assertTrue(h.logSink.lines.stream().anyMatch(l -> l.contains("\"result\":\"DOWNGRADED_A_TO_B\"")));
        assertTrue(core.isVerified(uuid));
    }

    @Test
    void optionATimeout_strictModeKicks() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness(b -> b.allowFallback(false));
        h.adapter.online(uuid, "Steve");
        withKey(h, uuid, kp, "desktop");
        ZeroTrustCore core = h.core();

        core.onAdminJoin(uuid, "Steve", CONN);
        h.scheduler.fireOnce();
        assertTrue(h.adapter.wasKicked(uuid));
        assertEquals(0, h.notifier.loginRequestCount());
        assertTrue(h.logSink.lines.stream().anyMatch(l -> l.contains("\"result\":\"FALLBACK_DENIED\"")));
    }

    @Test
    void optionATimeout_cancelledOnSuccess() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness(b -> b.allowFallback(true));
        h.adapter.online(uuid, "Steve");
        withKey(h, uuid, kp, "desktop");
        ZeroTrustCore core = h.core();

        core.onAdminJoin(uuid, "Steve", CONN);
        byte[] nonce = h.adapter.lastChallengeNonce(uuid);
        byte[] sig = CryptoTestKit.sign(kp.getPrivate(), DOMAIN, nonce);
        core.onSignatureResponse(uuid, CONN, nonce, sig);
        int before = h.notifier.loginRequestCount();
        h.scheduler.fireOnce();
        assertEquals(before, h.notifier.loginRequestCount(), "成功後逾時不應再觸發降級");
    }

    // (f) enroll requires console; upload with valid code+key adds; wrong code fails.
    @Test
    void enrollRequiresConsole() {
        UUID uuid = admin();
        TestHarness h = new TestHarness();
        assertFalse(h.core().enroll(uuid, false).success());
    }

    @Test
    void enrollEmitsIntegrationMarker() {
        UUID uuid = admin();
        TestHarness h = new TestHarness();
        CommandResult r = h.core().enroll(uuid, true);
        assertTrue(r.success());
        assertTrue(r.message().contains("Enrollment code for " + uuid + ": "), "訊息: " + r.message());
    }

    @Test
    void uploadWithValidCodeAddsKey() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness();
        CommandResult enroll = h.core().enroll(uuid, true);
        String code = extractCode(enroll.message(), uuid);

        CommandResult up = h.core().upload(uuid, CryptoTestKit.encodePublicKeyBase64(kp.getPublic()), code, "desktop");
        assertTrue(up.success(), up.message());
        h.adapter.online(uuid, "Steve");
        h.core().onAdminJoin(uuid, "Steve", CONN);
        assertTrue(h.adapter.wasChallenged(uuid));
    }

    @Test
    void uploadWithWrongCodeFails() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness();
        h.core().enroll(uuid, true);
        CommandResult up = h.core().upload(uuid, CryptoTestKit.encodePublicKeyBase64(kp.getPublic()),
                "00000000000000000000000000000000", "desktop");
        assertFalse(up.success());
    }

    @Test
    void uploadWithInvalidKeyFails() {
        UUID uuid = admin();
        TestHarness h = new TestHarness();
        CommandResult enroll = h.core().enroll(uuid, true);
        String code = extractCode(enroll.message(), uuid);
        CommandResult up = h.core().upload(uuid, CryptoTestKit.rsaPublicKeyBase64(), code, "desktop");
        assertFalse(up.success(), "RSA 公鑰應被拒絕");
    }

    @Test
    void uploadExpiredCodeFails() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness(b -> b.enrollmentTokenTtl(Duration.ofMinutes(10)));
        CommandResult enroll = h.core().enroll(uuid, true);
        String code = extractCode(enroll.message(), uuid);
        h.clock.advance(Duration.ofMinutes(11));
        CommandResult up = h.core().upload(uuid, CryptoTestKit.encodePublicKeyBase64(kp.getPublic()), code, "desktop");
        assertFalse(up.success());
        assertTrue(up.message().contains("過期"));
    }

    // (g) rotate fails unless session verified via SIGNATURE_A.
    @Test
    void rotateFailsWhenNotVerified() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness();
        assertFalse(h.core().rotate(uuid, CryptoTestKit.encodePublicKeyBase64(kp.getPublic()), "new").success());
    }

    @Test
    void rotateSucceedsWhenVerifiedViaSignatureA() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness();
        withKey(h, uuid, kp, "desktop");
        ZeroTrustCore core = h.core();
        core.onAdminJoin(uuid, "Steve", CONN);
        byte[] nonce = h.adapter.lastChallengeNonce(uuid);
        core.onSignatureResponse(uuid, CONN, nonce, CryptoTestKit.sign(kp.getPrivate(), DOMAIN, nonce));
        assertTrue(core.isVerified(uuid));

        KeyPair fresh = CryptoTestKit.generateEd25519();
        CommandResult r = core.rotate(uuid, CryptoTestKit.encodePublicKeyBase64(fresh.getPublic()), "laptop");
        assertTrue(r.success(), r.message());
    }

    @Test
    void rotateFailsWhenVerifiedViaOptionB() {
        UUID uuid = admin();
        TestHarness h = new TestHarness(b -> b.allowFallback(true));
        h.adapter.online(uuid, "Steve");
        h.notifier.nextResult(ConfirmResult.CONFIRMED);
        h.core().onAdminJoin(uuid, "Steve", CONN);
        assertTrue(h.core().isVerified(uuid));

        KeyPair fresh = CryptoTestKit.generateEd25519();
        CommandResult r = h.core().rotate(uuid, CryptoTestKit.encodePublicKeyBase64(fresh.getPublic()), "x");
        assertFalse(r.success(), "選項 B 驗證者不得換鑰");
    }

    // (h) revoke (console) of a VERIFIED admin → revokeAdminPerm + kick + EMERGENCY alert.
    @Test
    void revokeVerifiedAdminKicksAndAlerts() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness();
        withKey(h, uuid, kp, "desktop");
        ZeroTrustCore core = h.core();
        h.adapter.online(uuid, "Steve");
        core.onAdminJoin(uuid, "Steve", CONN);
        byte[] nonce = h.adapter.lastChallengeNonce(uuid);
        core.onSignatureResponse(uuid, CONN, nonce, CryptoTestKit.sign(kp.getPrivate(), DOMAIN, nonce));
        assertTrue(core.isVerified(uuid));

        CommandResult r = core.revoke(uuid, null, true);
        assertTrue(r.success());
        assertTrue(h.adapter.wasRevoked(uuid));
        assertTrue(h.adapter.wasKicked(uuid));
        assertTrue(h.notifier.hasAlert(AlertLevel.EMERGENCY));
    }

    @Test
    void revokeRequiresConsole() {
        UUID uuid = admin();
        TestHarness h = new TestHarness();
        assertFalse(h.core().revoke(uuid, null, false).success());
    }

    // (i) shutdown → revokeAdminPerm for all verified.
    @Test
    void shutdownRevokesAllVerified() {
        TestHarness h = new TestHarness();
        UUID a = admin();
        UUID b = admin();
        KeyPair ka = CryptoTestKit.generateEd25519();
        KeyPair kb = CryptoTestKit.generateEd25519();
        withKey(h, a, ka, "d");
        withKey(h, b, kb, "d");
        ZeroTrustCore core = h.core();
        core.onAdminJoin(a, "P", CONN);
        core.onAdminJoin(b, "Q", CONN);
        byte[] na = h.adapter.lastChallengeNonce(a);
        // 取得各自的 Nonce：lastChallengeNonce(a) 只回傳 a 的最後一個。
        byte[] nb = h.adapter.lastChallengeNonce(b);
        core.onSignatureResponse(a, CONN, na, CryptoTestKit.sign(ka.getPrivate(), DOMAIN, na));
        core.onSignatureResponse(b, CONN, nb, CryptoTestKit.sign(kb.getPrivate(), DOMAIN, nb));
        assertTrue(core.isVerified(a));
        assertTrue(core.isVerified(b));

        core.shutdown();
        assertTrue(h.adapter.wasRevoked(a));
        assertTrue(h.adapter.wasRevoked(b));
        assertTrue(h.logSink.isClosed(), "shutdown 應關閉 LogSink");
    }

    // (j) safe mode → join does not grant (stays frozen/denied).
    @Test
    void safeMode_joinNeverGrants() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness();
        withKey(h, uuid, kp, "desktop");
        ZeroTrustCore core = h.core();
        core.enterSafeMode("self-test failed");
        assertTrue(core.isSafeMode());

        core.onAdminJoin(uuid, "Steve", CONN);
        assertTrue(core.isFrozen(uuid), "安全模式應凍結");
        assertFalse(h.adapter.wasChallenged(uuid), "安全模式不應啟動任何驗證");
        assertFalse(h.adapter.wasGranted(uuid));

        byte[] nonce = new byte[32];
        byte[] sig = CryptoTestKit.signRaw(kp.getPrivate(), nonce);
        core.onSignatureResponse(uuid, CONN, nonce, sig);
        assertFalse(h.adapter.wasGranted(uuid));
        assertFalse(core.isVerified(uuid));
    }

    // (k) session TTL expiry (advance clock + run cleanup) → revokeAdminPerm called.
    @Test
    void sessionTtlExpiryRevokes() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness(b -> b.sessionTtl(Duration.ofHours(4)));
        withKey(h, uuid, kp, "desktop");
        ZeroTrustCore core = h.core();
        h.adapter.online(uuid, "Steve");
        core.onAdminJoin(uuid, "Steve", CONN);
        byte[] nonce = h.adapter.lastChallengeNonce(uuid);
        core.onSignatureResponse(uuid, CONN, nonce, CryptoTestKit.sign(kp.getPrivate(), DOMAIN, nonce));
        assertTrue(core.isVerified(uuid));

        h.clock.advance(Duration.ofHours(4).plusSeconds(1));
        h.scheduler.tickRepeating();
        assertTrue(h.adapter.wasRevoked(uuid), "Session 到期應撤回權限");
        assertFalse(core.isVerified(uuid));
    }

    // (l) frozen packet flood beyond limit → kick.
    @Test
    void frozenPacketFloodKicks() {
        UUID uuid = admin();
        TestHarness h = new TestHarness(b -> {
            b.allowFallback(true);
            b.freezePacketLimitPerSecond(20);
        });
        h.adapter.online(uuid, "Steve");
        h.notifier.nextResult(null); // 使選項 B 不自動完成，保持凍結。
        h.core().onAdminJoin(uuid, "Steve", CONN);
        assertTrue(h.core().isFrozen(uuid));

        boolean kicked = false;
        for (int i = 0; i < 25; i++) {
            assertTrue(h.core().onFrozenPacket(uuid), "凍結期間封包一律攔截");
            if (h.adapter.wasKicked(uuid)) {
                kicked = true;
                break;
            }
        }
        assertTrue(kicked, "超過速率上限應踢出");
        assertTrue(h.notifier.hasAlert(AlertLevel.HIGH));
    }

    @Test
    void frozenPacketReturnsFalseWhenNotFrozen() {
        UUID uuid = admin();
        TestHarness h = new TestHarness();
        assertFalse(h.core().onFrozenPacket(uuid));
    }

    @Test
    void listEmptyWhenNoKeys() {
        UUID uuid = admin();
        TestHarness h = new TestHarness();
        CommandResult r = h.core().list(uuid);
        assertTrue(r.success());
        assertTrue(r.message().contains("無已登記金鑰"));
    }

    @Test
    void listShowsKeys() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness();
        withKey(h, uuid, kp, "desktop");
        CommandResult r = h.core().list(uuid);
        assertTrue(r.success());
        assertTrue(r.message().contains("desktop"));
    }

    @Test
    void trustedResumeOnQuickReconnect() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness();
        withKey(h, uuid, kp, "desktop");
        ZeroTrustCore core = h.core();
        h.adapter.online(uuid, "Steve");
        core.onAdminJoin(uuid, "Steve", CONN);
        byte[] nonce = h.adapter.lastChallengeNonce(uuid);
        core.onSignatureResponse(uuid, CONN, nonce, CryptoTestKit.sign(kp.getPrivate(), DOMAIN, nonce));
        assertTrue(core.isVerified(uuid));

        core.onAdminQuit(uuid);
        assertTrue(h.adapter.wasRevoked(uuid));

        h.clock.advance(Duration.ofMinutes(5));
        h.adapter.online(uuid, "Steve");
        core.onAdminJoin(uuid, "Steve", "conn-2");
        byte[] nonce2 = h.adapter.lastChallengeNonce(uuid);
        core.onSignatureResponse(uuid, "conn-2", nonce2, CryptoTestKit.sign(kp.getPrivate(), DOMAIN, nonce2));
        assertTrue(core.isVerified(uuid));
        assertTrue(h.logSink.lines.stream().anyMatch(l -> l.contains("\"result\":\"TRUSTED_RESUME\"")));
    }

    @Test
    void verifyReentersFrozenAndChallenges() {
        UUID uuid = admin();
        KeyPair kp = CryptoTestKit.generateEd25519();
        TestHarness h = new TestHarness();
        withKey(h, uuid, kp, "desktop");
        ZeroTrustCore core = h.core();
        h.adapter.online(uuid, "Steve");
        // 直接呼叫 verify（在線重新驗證）。
        CommandResult r = core.verify(uuid, CONN);
        assertTrue(r.success());
        assertTrue(core.isFrozen(uuid));
        assertTrue(h.adapter.wasChallenged(uuid));
    }

    private static String extractCode(String enrollMessage, UUID uuid) {
        String marker = "Enrollment code for " + uuid + ": ";
        int idx = enrollMessage.indexOf(marker);
        assertTrue(idx >= 0, "marker not found in: " + enrollMessage);
        String rest = enrollMessage.substring(idx + marker.length());
        return rest.substring(0, 32);
    }
}
