package com.github.guoyyit.compactblockrelayjava.relay.protocol;

import com.github.guoyyit.compactblockrelayjava.relay.model.BlockHeader;

import java.util.List;

/**
 * 紧凑区块通知：只携带区块头、短标识密钥 nonce 与每笔交易的短标识。
 */
public final class CompactBlockAnnouncement {

    private final BlockHeader header;
    private final long nonce;
    private final List<byte[]> shortTxIds;

    public CompactBlockAnnouncement(BlockHeader header, long nonce, List<byte[]> shortTxIds) {
        this.header = header;
        this.nonce = nonce;
        this.shortTxIds = shortTxIds;
    }

    public BlockHeader getHeader() {
        return header;
    }

    public long getNonce() {
        return nonce;
    }

    public List<byte[]> getShortTxIds() {
        return shortTxIds;
    }
}
