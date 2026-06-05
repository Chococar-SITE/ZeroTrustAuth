package com.chococar.zerotrust.notify;

/** 選項 B 帶外確認的結果。 */
public enum ConfirmResult {
    /** 管理員點擊「✅ 確認是我」。 */
    CONFIRMED,
    /** 管理員點擊「❌ 不是我」——觸發緊急警報。 */
    DENIED,
    /** TTL 內未回應。 */
    TIMEOUT,
    /** DM 發送失敗（管理員關閉私訊、Bot 無權限等）。 */
    SEND_FAILED
}
