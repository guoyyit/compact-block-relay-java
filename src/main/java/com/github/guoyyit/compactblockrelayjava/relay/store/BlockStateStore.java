package com.github.guoyyit.compactblockrelayjava.relay.store;

import com.github.guoyyit.compactblockrelayjava.relay.model.Block;

import java.util.List;

/**
 * 区块状态持久化：已确认区块与未完成的紧凑重建必须可区分，崩溃重启后可恢复。
 */
public interface BlockStateStore {

    void savePending(String blockHash, long height, String prevBlockHash);

    void markConfirmed(Block block);

    void removePending(String blockHash);

    List<PendingBlock> loadPending();

    boolean isConfirmed(String blockHash);

    Block getConfirmed(String blockHash);

    String getTipHash();

    long getTipHeight();

    record PendingBlock(String blockHash, long height, String prevBlockHash) {
    }
}
