package com.github.guoyyit.compactblockrelayjava.protocol;

import com.github.guoyyit.compactblockrelayjava.model.BlockHeader;

import java.util.List;

/**
 * 新区块紧凑通知：区块头 + 每笔交易的 64 位短标识 + SipHash 密钥。
 */
public record AnnounceMessage(String senderId, String recipientId, BlockHeader header,
                              List<Long> shortIds, long k0, long k1) implements Message {
}
