package com.github.guoyyit.compactblockrelayjava.crypto;

import java.nio.charset.StandardCharsets;

/**
 * 交易短标识：SipHash-2-4(k0, k1, txid 字节)，与 BIP152 方案一致。
 *
 * <p>64 位短标识在百万级交易池内碰撞概率极低（约 1e-8/区块量级），
 * 而通知开销仅为完整 txid（256 位）的 1/4。碰撞由上层重建逻辑安全处理。
 */
public final class SipHash {
    private SipHash() {
    }

    public static long shortId(long k0, long k1, String txid) {
        return hash(k0, k1, txid.getBytes(StandardCharsets.UTF_8));
    }

    static long hash(long k0, long k1, byte[] data) {
        long v0 = 0x736f6d6570736575L ^ k0;
        long v1 = 0x646f72616e646f6dL ^ k1;
        long v2 = 0x6c7967656e657261L ^ k0;
        long v3 = 0x7465646279746573L ^ k1;

        int fullBlocks = data.length / 8;
        for (int block = 0; block < fullBlocks; block++) {
            long m = readLittleEndianLong(data, block * 8);
            v3 ^= m;
            long[] s1 = sipRound(v0, v1, v2, v3);
            v0 = s1[0]; v1 = s1[1]; v2 = s1[2]; v3 = s1[3];
            long[] s2 = sipRound(v0, v1, v2, v3);
            v0 = s2[0]; v1 = s2[1]; v2 = s2[2]; v3 = s2[3];
            v0 ^= m;
        }

        int offset = fullBlocks * 8;
        long last = (long) (data.length & 0xFF) << 56;
        for (int i = offset; i < data.length; i++) {
            last |= (data[i] & 0xFFL) << (8 * (i - offset));
        }
        v3 ^= last;
        long[] s3 = sipRound(v0, v1, v2, v3);
        v0 = s3[0]; v1 = s3[1]; v2 = s3[2]; v3 = s3[3];
        long[] s4 = sipRound(v0, v1, v2, v3);
        v0 = s4[0]; v1 = s4[1]; v2 = s4[2]; v3 = s4[3];
        v0 ^= last;

        v2 ^= 0xFF;
        for (int i = 0; i < 4; i++) {
            long[] s = sipRound(v0, v1, v2, v3);
            v0 = s[0]; v1 = s[1]; v2 = s[2]; v3 = s[3];
        }
        return v0 ^ v1 ^ v2 ^ v3;
    }

    private static long readLittleEndianLong(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= (data[offset + i] & 0xFFL) << (8 * i);
        }
        return value;
    }

    private static long[] sipRound(long v0, long v1, long v2, long v3) {
        v0 += v1;
        v1 = Long.rotateLeft(v1, 13);
        v1 ^= v0;
        v0 = Long.rotateLeft(v0, 32);
        v2 += v3;
        v3 = Long.rotateLeft(v3, 16);
        v3 ^= v2;
        v0 += v3;
        v3 = Long.rotateLeft(v3, 21);
        v3 ^= v0;
        v2 += v1;
        v1 = Long.rotateLeft(v1, 17);
        v1 ^= v2;
        v2 = Long.rotateLeft(v2, 32);
        return new long[]{v0, v1, v2, v3};
    }
}
