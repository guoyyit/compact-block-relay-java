package com.github.guoyyit.compactblockrelayjava.relay.model;

/**
 * 一笔交易：txId 为交易内容的完整标识（十六进制哈希），payload 为交易完整内容。
 */
public final class Transaction {

    private final String txId;
    private final byte[] payload;

    public Transaction(String txId, byte[] payload) {
        this.txId = txId;
        this.payload = payload;
    }

    public String getTxId() {
        return txId;
    }

    public byte[] getPayload() {
        return payload;
    }
}
