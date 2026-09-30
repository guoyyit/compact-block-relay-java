package com.github.guoyyit.compactblockrelayjava.relay.protocol;

import com.github.guoyyit.compactblockrelayjava.relay.model.Transaction;

import java.util.List;

/**
 * 缺失交易补齐响应。
 */
public final class TxnResponse {

    private final String blockHash;
    private final List<Transaction> transactions;

    public TxnResponse(String blockHash, List<Transaction> transactions) {
        this.blockHash = blockHash;
        this.transactions = transactions;
    }

    public String getBlockHash() {
        return blockHash;
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }
}
