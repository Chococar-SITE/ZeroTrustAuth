package com.chococar.zerotrust.auth;

import java.util.UUID;

/**
 * 零信任驗證引擎的對外契約——平台層（事件監聽、指令）呼叫此介面，
 * 引擎再透過 {@link com.chococar.zerotrust.platform.PlatformAdapter} 與
 * {@link com.chococar.zerotrust.notify.Notifier} 執行動作。
 *
 * <p>具體實作為 {@code AuthManager}。事件方法以動作（凍結 / 授權 / 踢出 / 警報）回應，
 * 指令方法回傳 {@link CommandResult} 供回饋使用者。
 */
public interface AuthEngine {

    // ── 連線生命週期 ─────────────────────────────────────

    /**
     * 管理員帳號連線。引擎立即凍結並啟動驗證（選項 A / B / 嚴格模式，計劃 3.4）。
     *
     * @param connectionId 該次連線的唯一識別，用於將 Nonce 綁定至此連線（計劃 3.2）。
     */
    void onAdminJoin(UUID uuid, String playerName, String connectionId);

    /** 管理員登出。立即撤銷權限；選項 A 已驗證者記入信任裝置窗口（計劃 4.6）。 */
    void onAdminQuit(UUID uuid);

    // ── 選項 A：簽名回應 ─────────────────────────────────

    /**
     * 客戶端針對挑戰回傳的簽名（已含領域前綴，由引擎以同一前綴驗證）。
     * 通過 → 解凍並授權；失敗 → 計入失敗次數，達上限踢出（計劃 3.1）。
     */
    void onSignatureResponse(UUID uuid, String connectionId, byte[] nonce, byte[] signature);

    // ── 凍結期間封包速率限制（計劃 6.4）─────────────────

    /**
     * 凍結期間收到一個非驗證封包。
     *
     * @return {@code true} 表示應攔截（取消）該封包；超過速率上限時引擎會踢出並警報。
     */
    boolean onFrozenPacket(UUID uuid);

    // ── /authkey 指令（計劃 3.7）─────────────────────────

    /** {@code /authkey enroll <uuid>}（僅主控台）：產生一次性註冊碼。 */
    CommandResult enroll(UUID target, boolean fromConsole);

    /** {@code /authkey upload <pubkey> <code> [label]}：帶註冊碼上傳新公鑰。 */
    CommandResult upload(UUID self, String base64PublicKey, String enrollmentCode, String label);

    /**
     * {@code /authkey rotate <newPubkey> [label]}：換鑰，免註冊碼。
     * 要求目前 Session 已以選項 A（Ed25519）驗證，以「既有金鑰」證明身份（計劃 3.5）。
     */
    CommandResult rotate(UUID self, String base64NewPublicKey, String label);

    /** {@code /authkey verify}：在線重新驗證（Session 到期後恢復權限，計劃 4.7）。 */
    CommandResult verify(UUID self, String connectionId);

    /** {@code /authkey list}：列出自己名下所有金鑰、label 與最後使用時間。 */
    CommandResult list(UUID self);

    /**
     * {@code /authkey revoke <uuid> [label]}（僅主控台）：撤銷金鑰並立即終止活躍 Session。
     * {@code label} 為 {@code null} 時撤銷該帳號全部金鑰（計劃 6.5）。
     */
    CommandResult revoke(UUID target, String label, boolean fromConsole);

    // ── 狀態查詢 / 生命週期 ───────────────────────────────

    boolean isFrozen(UUID uuid);

    boolean isVerified(UUID uuid);

    /** 外掛停用（{@code onDisable}）：主動撤回所有權限並清空 Session（fail-closed，計劃 5.2）。 */
    void shutdown();
}
