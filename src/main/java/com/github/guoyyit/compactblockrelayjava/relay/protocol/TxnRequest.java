package com.github.guoyyit.compactblockrelayjava.relay.protocol;

import java.util.List;

/**
 * 缺失交易补齐请求：按完整交易标识批量请求，多个缺失合并为一次请求。
 */
public final class TxnRequest {

    private final String blockHash;
    private final List<String> missingTxIds;

    public TxnRequest(String blockHash, List<String> missingTxIds) {
        this.blockHash = blockHash;
        this.missingTxIds = missingTxIds;
    }

    public String getBlockHash() {
        return blockHash;
    }

    public List<String> getMissingTxIds() {
        return missingTxIds;
    }
}
