package com.github.guoyyit.compactblockrelayjava.model;

import java.util.List;

/**
 * 完整区块：区块头 + 与 header.txids() 同序的完整交易列表。
 */
public record Block(BlockHeader header, List<Transaction> transactions) {
}
