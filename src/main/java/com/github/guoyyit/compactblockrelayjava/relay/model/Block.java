package com.github.guoyyit.compactblockrelayjava.relay.model;

import java.util.List;

/**
 * 完整区块：区块头 + 与区块头声明顺序一致的完整交易列表。
 */
public final class Block {

    private final BlockHeader header;
    private final List<Transaction> transactions;

    public Block(BlockHeader header, List<Transaction> transactions) {
        this.header = header;
        this.transactions = transactions;
    }

    public BlockHeader getHeader() {
        return header;
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }
}
