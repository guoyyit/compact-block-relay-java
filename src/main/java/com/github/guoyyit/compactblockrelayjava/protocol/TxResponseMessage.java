package com.github.guoyyit.compactblockrelayjava.protocol;

import java.util.List;

/**
 * 缺失交易补齐响应。
 */
public record TxResponseMessage(String senderId, String recipientId, String blockHash,
                                List<IndexedTx> transactions) implements Message {
}
