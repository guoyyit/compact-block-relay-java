package com.github.guoyyit.compactblockrelayjava.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 哈希工具：交易 txid 使用 SHA-256(data) 的十六进制表示。
 */
public final class Hashes {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashes() {
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(byte[] data) {
        return toHex(sha256(data));
    }

    public static String txidOf(byte[] data) {
        return sha256Hex(data);
    }

    public static String txidOf(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    public static byte[] fromHex(String hex) {
        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("hex length must be even: " + hex);
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("invalid hex: " + hex);
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }
}
