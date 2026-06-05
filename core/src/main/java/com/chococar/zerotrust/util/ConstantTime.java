package com.chococar.zerotrust.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 常數時間比較，避免以執行時間側通道洩漏 Nonce / Token 內容。
 * 底層委派 {@link MessageDigest#isEqual(byte[], byte[])}（JDK 自身即為常數時間實作）。
 */
public final class ConstantTime {

    private ConstantTime() {}

    /** 常數時間位元組比較；{@code null} 視為不相等。 */
    public static boolean equals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a, b);
    }

    /** 常數時間字串比較（以 UTF-8 編碼後比較位元組）。 */
    public static boolean equals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
