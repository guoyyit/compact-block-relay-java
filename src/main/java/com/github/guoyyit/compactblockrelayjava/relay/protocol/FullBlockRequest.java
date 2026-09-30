package com.github.guoyyit.compactblockrelayjava.relay.protocol;

/**
 * 完整区块回退请求。
 */
public final class FullBlockRequest {

    private final String blockHash;

    public FullBlockRequest(String blockHash) {
        this.blockHash = blockHash;
    }

    public String getBlockHash() {
        return blockHash;
    }
}
