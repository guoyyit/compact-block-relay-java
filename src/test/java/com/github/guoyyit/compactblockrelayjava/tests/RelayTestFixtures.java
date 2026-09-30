package com.github.guoyyit.compactblockrelayjava.tests;

import com.github.guoyyit.compactblockrelayjava.relay.model.Block;
import com.github.guoyyit.compactblockrelayjava.relay.model.BlockHeader;
import com.github.guoyyit.compactblockrelayjava.relay.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.relay.util.Hashes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试数据构造工具。
 */
final class RelayTestFixtures {

    private RelayTestFixtures() {
    }

    static Transaction tx(String seed) {
        byte[] payload = ("tx-" + seed).getBytes(StandardCharsets.UTF_8);
        return new Transaction(Hashes.toHex(Hashes.sha256(payload)), payload);
    }

    static List<Transaction> txs(int count) {
        return txs("t", count);
    }

    static List<Transaction> txs(String prefix, int count) {
        List<Transaction> transactions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            transactions.add(tx(prefix + i));
        }
        return transactions;
    }

    static String blockHash(long height, String prevHash, List<String> txIds) {
        StringBuilder material = new StringBuilder("block:").append(height).append(':');
        material.append(prevHash == null ? "GENESIS" : prevHash);
        for (String txId : txIds) {
            material.append('|').append(txId);
        }
        return Hashes.toHex(Hashes.sha256(Hashes.utf8(material.toString())));
    }

    static Block block(long height, String prevHash, List<Transaction> transactions) {
        List<String> txIds = transactions.stream().map(Transaction::getTxId).toList();
        BlockHeader header = new BlockHeader(blockHash(height, prevHash, txIds),
                height, prevHash, txIds);
        return new Block(header, transactions);
    }

    /** 用与协议相同的短标识算法手工构造紧凑通知，便于碰撞测试定制。 */
    static List<byte[]> shortIdsFor(List<String> txIds, long nonce, int shortBytes) {
        com.github.guoyyit.compactblockrelayjava.relay.util.ShortTxIdCalculator calc =
                new com.github.guoyyit.compactblockrelayjava.relay.util.ShortTxIdCalculator(shortBytes);
        List<byte[]> result = new ArrayList<>(txIds.size());
        for (String txId : txIds) {
            result.add(calc.compute(txId, nonce));
        }
        return result;
    }
}
