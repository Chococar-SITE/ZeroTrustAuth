package com.chococar.zerotrust.audit;

/** 驗證方式（審計日誌 {@code auth_method} 欄位，計劃 6.2）。 */
public enum AuthMethod {
    /** 選項 A：Ed25519 簽名。 */
    SIGNATURE_A,
    /** 選項 B：Discord 帶外確認。 */
    OUT_OF_BAND_B
}
