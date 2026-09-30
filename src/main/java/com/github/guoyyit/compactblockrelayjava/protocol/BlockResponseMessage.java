package com.github.guoyyit.compactblockrelayjava.protocol;

import com.github.guoyyit.compactblockrelayjava.model.Block;

/**
 * 完整区块回退响应。
 */
public record BlockResponseMessage(String senderId, String recipientId, Block block) implements Message {
}
