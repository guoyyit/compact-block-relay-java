package com.github.guoyyit.compactblockrelayjava.relay.protocol;

import com.github.guoyyit.compactblockrelayjava.relay.model.Block;

/**
 * 完整区块回退响应。
 */
public final class FullBlockResponse {

    private final Block block;

    public FullBlockResponse(Block block) {
        this.block = block;
    }

    public Block getBlock() {
        return block;
    }
}
