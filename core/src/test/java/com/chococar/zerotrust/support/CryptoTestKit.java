package com.chococar.zerotrust.support;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * 測試專用加密輔助：產生 Ed25519 金鑰對、以私鑰簽 {@code signedMessage}、
 * 將公鑰編碼為 Base64（X.509/SPKI），並提供 RSA 公鑰供負面測試。
 *
 * <p>此處刻意獨立重算 {@code signedMessage = SHA-512(domain || nonce)}，
 * 以驗證 {@link com.chococar.zerotrust.auth.Ed25519Verifier} 的領域分隔與 wire 格式相符，
 * 模擬客戶端 Mod 的簽名行為。
 */
public final class CryptoTestKit {

    private CryptoTestKit() {}

    public static KeyPair generateEd25519() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String encodePublicKeyBase64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** 與引擎相同的領域分隔訊息：{@code SHA-512(domain_utf8 || nonce)}。 */
    public static byte[] signedMessage(String domain, byte[] nonce) {
        try {
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            sha512.update(domain.getBytes(StandardCharsets.UTF_8));
            sha512.update(nonce);
            return sha512.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 模擬客戶端：以私鑰對領域分隔後的訊息簽名。 */
    public static byte[] sign(PrivateKey priv, String domain, byte[] nonce) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(priv);
            s.update(signedMessage(domain, nonce));
            return s.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 直接對任意原始位元組以 Ed25519 簽名（用於負面測試：未加領域前綴）。 */
    public static byte[] signRaw(PrivateKey priv, byte[] message) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(priv);
            s.update(message);
            return s.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 產生一把 RSA 公鑰的 Base64（X.509），供金鑰類型混淆負面測試。 */
    public static String rsaPublicKeyBase64() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return Base64.getEncoder().encodeToString(g.generateKeyPair().getPublic().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
