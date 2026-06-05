package com.chococar.zerotrust.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519 簽名驗證與領域分隔（計劃 3.2 / 3.5）。純 JDK 17 實作。
 *
 * <ul>
 *   <li><b>領域分隔</b>：客戶端絕不簽裸 Nonce；固定簽 {@code SHA-512(domain || nonce)}，
 *       使簽名在 SSH / Git 等其他場景無效，封堵簽名預言機攻擊。</li>
 *   <li><b>金鑰類型防護</b>：{@link #parsePublicKey(String)} 僅接受合法 Ed25519 公鑰，
 *       拒絕 RSA / EC / DSA / 畸形編碼，防止金鑰類型混淆。</li>
 * </ul>
 */
public final class Ed25519Verifier {

    private static final String ALGORITHM = "Ed25519";

    /**
     * 解析 Base64（X.509/SPKI）編碼的 Ed25519 公鑰。
     *
     * @throws IllegalArgumentException 若 Base64 畸形、結構錯誤，或解析出的金鑰並非 Ed25519
     *                                  （如 RSA / EC / DSA）。
     */
    public PublicKey parsePublicKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalArgumentException("public key is empty");
        }
        final byte[] der;
        try {
            der = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("public key is not valid Base64", e);
        }

        final PublicKey key;
        try {
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
            key = factory.generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | RuntimeException e) {
            // KeyFactory("Ed25519") 對 RSA/EC/DSA SPKI 會丟 InvalidKeySpecException 等。
            throw new IllegalArgumentException("not a valid Ed25519 public key", e);
        }

        // 縱深防禦：即使某些 provider 寬鬆，仍明確驗證金鑰類型與曲線名稱。
        if (!(key instanceof EdECPublicKey)) {
            throw new IllegalArgumentException(
                    "key is not Ed25519 (got " + key.getClass().getName() + ")");
        }
        EdECPublicKey edKey = (EdECPublicKey) key;
        String paramName = edKey.getParams() == null ? null : edKey.getParams().getName();
        if (!ALGORITHM.equalsIgnoreCase(paramName)) {
            throw new IllegalArgumentException(
                    "key curve is not Ed25519 (got " + paramName + ")");
        }
        return key;
    }

    /**
     * 計算被簽名的訊息：{@code SHA-512(domain_utf8 || nonce)}（計劃 3.2 領域分隔）。
     */
    public byte[] signedMessage(String domain, byte[] nonce) {
        if (domain == null) {
            throw new IllegalArgumentException("domain == null");
        }
        if (nonce == null) {
            throw new IllegalArgumentException("nonce == null");
        }
        try {
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            sha512.update(domain.getBytes(StandardCharsets.UTF_8));
            sha512.update(nonce);
            return sha512.digest();
        } catch (GeneralSecurityException e) {
            // SHA-512 為 JDK 內建，理論上不會發生。
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    /**
     * 以指定公鑰驗證簽名是否為 {@link #signedMessage(String, byte[])} 的有效 Ed25519 簽名。
     * 任何例外（金鑰錯誤、簽名畸形等）一律視為驗證失敗（fail-closed）。
     */
    public boolean verify(String domain, byte[] nonce, byte[] signature, PublicKey key) {
        if (signature == null || key == null || nonce == null || domain == null) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(signedMessage(domain, nonce));
            return verifier.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }
}
