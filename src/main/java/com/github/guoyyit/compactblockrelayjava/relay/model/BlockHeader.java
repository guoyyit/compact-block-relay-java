package com.github.guoyyit.compactblockrelayjava.relay.model;

import java.util.List;

/**
 * 区块头：声明区块哈希、高度、前驱以及区块内每笔交易的完整标识列表。
 */
public final class BlockHeader {

    private final String blockHash;
    private final long height;
    private final String prevBlockHash;
    private final List<String> txIds;

    public BlockHeader(String blockHash, long height, String prevBlockHash, List<String> txIds) {
        this.blockHash = blockHash;
        this.height = height;
        this.prevBlockHash = prevBlockHash;
        this.txIds = txIds;
    }

    public String getBlockHash() {
        return blockHash;
    }

    public long getHeight() {
        return height;
    }

    public String getPrevBlockHash() {
        return prevBlockHash;
    }

    public List<String> getTxIds() {
        return txIds;
    }
}
