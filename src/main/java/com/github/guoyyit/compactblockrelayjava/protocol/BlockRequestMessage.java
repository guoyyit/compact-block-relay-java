package com.github.guoyyit.compactblockrelayjava.protocol;

/**
 * 完整区块回退请求。
 */
public record BlockRequestMessage(String senderId, String recipientId, String blockHash) implements Message {
}
