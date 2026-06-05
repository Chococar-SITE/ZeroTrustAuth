package com.chococar.zerotrust.audit;

/** 驗證結果（審計日誌 {@code result} 欄位，計劃 6.2）。 */
public enum AuthResult {
    SUCCESS,
    FAIL,
    /** 已從選項 A 降級至選項 B。 */
    DOWNGRADED_A_TO_B,
    /** 嚴格模式（{@code allow_fallback: false}）拒絕降級。 */
    FALLBACK_DENIED,
    /** 信任裝置 15 分鐘內免完整驗證重連。 */
    TRUSTED_RESUME
}
