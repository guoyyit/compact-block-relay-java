package com.github.guoyyit.compactblockrelayjava.protocol;

import java.util.List;

/**
 * 缺失交易补齐请求：多个缺失下标合并为一次请求。
 */
public record TxRequestMessage(String senderId, String recipientId, String blockHash,
                               List<Integer> indexes) implements Message {
}
