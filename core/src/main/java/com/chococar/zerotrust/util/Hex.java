package com.chococar.zerotrust.util;

/** 十六進位編解碼工具（純 JDK，無外部依賴）。 */
public final class Hex {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hex() {}

    /** 位元組陣列 → 小寫十六進位字串。 */
    public static String encode(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes == null");
        }
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /** 十六進位字串 → 位元組陣列（接受大小寫；長度須為偶數）。 */
    public static byte[] decode(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("hex == null");
        }
        int len = hex.length();
        if ((len & 1) != 0) {
            throw new IllegalArgumentException("hex length must be even");
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = digit(hex.charAt(i));
            int lo = digit(hex.charAt(i + 1));
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw new IllegalArgumentException("invalid hex char: " + c);
    }
}
