package com.github.guoyyit.compactblockrelayjava.storage;

import com.github.guoyyit.compactblockrelayjava.model.Block;

/**
 * 已确认接收区块的持久化存储。
 */
public interface BlockRepository {
    boolean contains(String blockHash);

    void save(Block block);

    Block loadTip();

    void close();
}
