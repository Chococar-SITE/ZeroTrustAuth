package com.chococar.zerotrust.auth;

/**
 * 管理員 Session 狀態機（計劃 4.1）。
 *
 * <pre>
 *   登入 → FROZEN ──驗證通過──▶ VERIFIED ──4h/登出──▶ EXPIRED / REVOKED
 *              └──失敗 3 次──▶ (踢出)
 * </pre>
 */
public enum SessionState {
    /** 剛登入或重新驗證中，等待驗證。僅能輸入 {@code /authkey}，其餘全部封鎖。 */
    FROZEN,
    /** 驗證通過，持有（transient）管理員權限。 */
    VERIFIED,
    /** Session 超過 TTL 自動失效，降為一般權限；可用 {@code /authkey verify} 在線重驗。 */
    EXPIRED,
    /** 登出或被強制撤銷，需重新登入並驗證。 */
    REVOKED
}
