package com.github.guoyyit.compactblockrelayjava.relay.util;

import java.util.Arrays;

/**
 * 短交易标识计算：对完整交易标识（十六进制）结合区块 nonce 再做一次 SHA-256，
 * 取结果前 N 个字节作为短标识。
 *
 * <p>默认 6 字节（48 bit）：按生日碰撞模型，单个区块约 3000 笔交易时，
 * 区块内自碰撞概率约 1e-6；即便发生碰撞，重建后的逐笔完整标识校验也能安全发现。
 */
public final class ShortTxIdCalculator {

    private final int shortTxIdBytes;

    public ShortTxIdCalculator(int shortTxIdBytes) {
        if (shortTxIdBytes < 1 || shortTxIdBytes > 32) {
            throw new IllegalArgumentException("shortTxIdBytes must be within [1,32]: " + shortTxIdBytes);
        }
        this.shortTxIdBytes = shortTxIdBytes;
    }

    public byte[] compute(String txId, long nonce) {
        byte[] base = Hashes.fromHex(txId);
        byte[] mixed = Hashes.sha256(base, longBytes(nonce));
        return Arrays.copyOf(mixed, shortTxIdBytes);
    }

    private static byte[] longBytes(long value) {
        return new byte[] {
                (byte) value,
                (byte) (value >> 8),
                (byte) (value >> 16),
                (byte) (value >> 24),
                (byte) (value >> 32),
                (byte) (value >> 40),
                (byte) (value >> 48),
                (byte) (value >> 56)
        };
    }
}
