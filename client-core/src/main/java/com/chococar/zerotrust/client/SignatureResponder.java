package com.chococar.zerotrust.client;

import java.util.Objects;

/**
 * 選項 A 的自動簽名回應（計劃 5.6）。客戶端 Mod 收到伺服器的 Nonce 挑戰封包後，
 * 呼叫 {@link #respond(byte[])} 取得簽名再回傳——玩家無需任何操作。
 *
 * <p>本類別與遊戲載入器無關；封包編解碼由各載入器的客戶端 Mod 負責。
 */
public final class SignatureResponder {

    private final ClientIdentity identity;
    private final String signatureDomain;

    /**
     * @param identity        客戶端金鑰
     * @param signatureDomain 領域前綴，須與伺服器設定一致（預設 {@code MC-ZEROTRUST-AUTH-v1:}）
     */
    public SignatureResponder(ClientIdentity identity, String signatureDomain) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.signatureDomain = Objects.requireNonNull(signatureDomain, "signatureDomain");
    }

    /** 對挑戰 Nonce 回傳簽名（已含領域前綴）。 */
    public byte[] respond(byte[] nonce) {
        return identity.sign(signatureDomain, nonce);
    }

    /** 可上傳的公鑰（Base64）。 */
    public String publicKeyBase64() {
        return identity.publicKeyBase64();
    }
}
