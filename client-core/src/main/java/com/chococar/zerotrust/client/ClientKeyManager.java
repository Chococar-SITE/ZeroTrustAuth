package com.chococar.zerotrust.client;

import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 客戶端金鑰來源管理（計劃 5.6.1）。產生專用 Ed25519 金鑰，或複用本機 OpenSSH Ed25519 私鑰。
 * 平台無關、可單元測試；私鑰永不離開客戶端。
 *
 * <p>目前支援<b>未加密</b>的 OpenSSH Ed25519 私鑰（{@code id_ed25519}）。
 * 加密（passphrase）私鑰請先解密或改用 {@link #generate()} 專用金鑰（計劃建議）。
 */
public final class ClientKeyManager {

    /** Ed25519 X.509/SPKI 固定前綴：SEQ{ SEQ{ OID 1.3.101.112 } BITSTRING(33,0) }。 */
    private static final byte[] X509_ED25519_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private ClientKeyManager() {}

    /** 產生 Minecraft 專用 Ed25519 金鑰對（建議模式）。 */
    public static ClientIdentity generate() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            KeyPair kp = kpg.generateKeyPair();
            String pubB64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
            return new ClientIdentity(kp.getPrivate(), pubB64);
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 金鑰產生失敗", e);
        }
    }

    /**
     * 從未加密的 OpenSSH Ed25519 私鑰（{@code id_ed25519}，PEM 類型 {@code OPENSSH PRIVATE KEY}）載入。
     *
     * @throws IllegalArgumentException 非合法/非 Ed25519/加密的 OpenSSH 私鑰
     */
    public static ClientIdentity fromOpenSshEd25519(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("OpenSSH private key is empty");
        }
        byte[] blob = decodePemBody(pem, "OPENSSH PRIVATE KEY");
        final AsymmetricKeyParameter parsed;
        try {
            parsed = OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(blob);
        } catch (RuntimeException e) {
            // 加密私鑰或不支援的格式會在此失敗。
            throw new IllegalArgumentException(
                    "無法解析 OpenSSH 私鑰（可能已加密或非 Ed25519）：" + e.getMessage(), e);
        }
        if (!(parsed instanceof Ed25519PrivateKeyParameters ed)) {
            throw new IllegalArgumentException("OpenSSH 私鑰非 Ed25519（僅支援 id_ed25519）");
        }
        byte[] seed = ed.getEncoded();                 // 32-byte 私鑰種子
        byte[] rawPub = ed.generatePublicKey().getEncoded(); // 32-byte 公鑰
        try {
            EdECPrivateKeySpec spec = new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed);
            var privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(spec);
            String pubB64 = publicKeyBase64FromRaw(rawPub);
            return new ClientIdentity(privateKey, pubB64);
        } catch (Exception e) {
            throw new IllegalArgumentException("OpenSSH Ed25519 轉換失敗：" + e.getMessage(), e);
        }
    }

    /** 從本機持久化的 PKCS#8 私鑰（Base64）＋公鑰（Base64）重建身份（見 {@link ClientKeyStore}）。 */
    public static ClientIdentity fromStored(String publicKeyBase64, String privateKeyPkcs8Base64) {
        try {
            byte[] der = Base64.getDecoder().decode(privateKeyPkcs8Base64);
            PrivateKey priv = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
            return new ClientIdentity(priv, publicKeyBase64);
        } catch (Exception e) {
            throw new IllegalArgumentException("無法重建已儲存的金鑰：" + e.getMessage(), e);
        }
    }

    /** 將 32-byte 原始 Ed25519 公鑰封裝為 X.509/SPKI 並 Base64（伺服器上傳格式）。 */
    public static String publicKeyBase64FromRaw(byte[] rawPub) {
        if (rawPub == null || rawPub.length != 32) {
            throw new IllegalArgumentException("raw Ed25519 public key must be 32 bytes");
        }
        byte[] spki = new byte[X509_ED25519_PREFIX.length + 32];
        System.arraycopy(X509_ED25519_PREFIX, 0, spki, 0, X509_ED25519_PREFIX.length);
        System.arraycopy(rawPub, 0, spki, X509_ED25519_PREFIX.length, 32);
        return Base64.getEncoder().encodeToString(spki);
    }

    /** 抽出 PEM body 並 Base64 解碼。 */
    private static byte[] decodePemBody(String pem, String type) {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int b = pem.indexOf(begin);
        int e = pem.indexOf(end);
        if (b < 0 || e < 0 || e <= b) {
            throw new IllegalArgumentException("缺少 " + type + " PEM 標頭/結尾");
        }
        String body = pem.substring(b + begin.length(), e).replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("PEM body 非合法 Base64", ex);
        }
    }
}
