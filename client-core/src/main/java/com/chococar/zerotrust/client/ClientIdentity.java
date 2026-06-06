package com.chococar.zerotrust.client;

import com.chococar.zerotrust.auth.Ed25519Verifier;

import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/**
 * 一把客戶端身份金鑰：私鑰（永不離開本機）+ 對應公鑰的 Base64（X.509）。
 *
 * <p>{@link #sign(String, byte[])} <b>固定加領域前綴</b>後簽名（簽
 * {@code SHA-512(domain || nonce)}），與伺服器 {@link Ed25519Verifier} 完全一致——
 * 絕不簽裸 Nonce，封堵簽名預言機（計劃 3.2）。
 */
public final class ClientIdentity {

    private final PrivateKey privateKey;
    private final String publicKeyBase64;
    private final Ed25519Verifier verifier = new Ed25519Verifier();

    public ClientIdentity(PrivateKey privateKey, String publicKeyBase64) {
        this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
        this.publicKeyBase64 = Objects.requireNonNull(publicKeyBase64, "publicKeyBase64");
    }

    /** 可上傳至伺服器的公鑰（Base64 X.509/SPKI）。 */
    public String publicKeyBase64() {
        return publicKeyBase64;
    }

    /** 私鑰的 PKCS#8 編碼（Base64）——僅供本機持久化，<b>絕不</b>傳輸或上傳。 */
    public String privateKeyPkcs8Base64() {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }

    /** 對挑戰 Nonce 加領域前綴後簽名，回傳 Ed25519 簽名。 */
    public byte[] sign(String domain, byte[] nonce) {
        try {
            byte[] message = verifier.signedMessage(domain, nonce);
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(privateKey);
            s.update(message);
            return s.sign();
        } catch (Exception e) {
            throw new IllegalStateException("簽名失敗", e);
        }
    }
}
