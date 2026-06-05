package com.chococar.zerotrust.audit;

import com.chococar.zerotrust.util.Hex;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * IP 位址雜湊（計劃 6.2 / 安全不變式 9）。
 *
 * <p>必須使用 <b>HMAC-SHA256 + 伺服器密鑰鹽</b>，<b>絕不</b>使用裸 {@code sha256(ip)}——
 * IPv4 僅約 43 億種組合，裸雜湊秒級即可暴力還原。密鑰鹽從環境變數 {@code IP_HMAC_SECRET}
 * 注入（不寫入設定檔或程式碼）。
 */
public final class IpHasher {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private IpHasher() {}

    /**
     * 計算 {@code HMAC-SHA256(secret, ip)} 的小寫十六進位字串。
     *
     * @param secret 伺服器密鑰鹽（來自環境變數 {@code IP_HMAC_SECRET}）
     * @param ip     IP 位址字串
     * @return 64 字元小寫十六進位
     */
    public static String hmacSha256Hex(byte[] secret, String ip) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("ip hmac secret is empty");
        }
        if (ip == null) {
            throw new IllegalArgumentException("ip == null");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(ip.getBytes(StandardCharsets.UTF_8));
            return Hex.encode(digest);
        } catch (GeneralSecurityException e) {
            // HmacSHA256 為 JDK 內建，理論上不會發生。
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
