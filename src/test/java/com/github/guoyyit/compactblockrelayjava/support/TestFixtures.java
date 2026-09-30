package com.github.guoyyit.compactblockrelayjava.support;

import com.github.guoyyit.compactblockrelayjava.crypto.Hashes;
import com.github.guoyyit.compactblockrelayjava.crypto.SipHash;
import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.model.BlockHeader;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.protocol.AnnounceMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 测试夹具：构造合法交易/区块/紧凑通知，以及异步等待工具。
 */
public final class TestFixtures {
    private TestFixtures() {
    }

    public static Transaction tx(String content) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        return new Transaction(Hashes.sha256Hex(data), data);
    }

    public static List<Transaction> txs(String... contents) {
        List<Transaction> transactions = new ArrayList<>();
        for (String content : contents) {
            transactions.add(tx(content));
        }
        return transactions;
    }

    public static Block block(String hash, String prevHash, long height, List<Transaction> transactions) {
        List<String> txids = transactions.stream().map(Transaction::txid).toList();
        long nonce = new Random(hash.hashCode()).nextLong();
        return new Block(new BlockHeader(hash, prevHash, height, txids, nonce), List.copyOf(transactions));
    }

    public static AnnounceMessage announce(String senderId, String recipientId,
                                           Block block, long k0, long k1) {
        List<Long> shortIds = block.header().txids().stream()
                .map(txid -> SipHash.shortId(k0, k1, txid))
                .toList();
        return new AnnounceMessage(senderId, recipientId, block.header(), shortIds, k0, k1);
    }

    /**
     * 轮询等待断言条件成立，适配事件循环异步处理。
     */
    public static void waitFor(long timeoutMs, Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        AssertionError last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.check()) {
                    return;
                }
            } catch (AssertionError error) {
                last = error;
            }
            Thread.sleep(10);
        }
        if (last != null) {
            throw last;
        }
        throw new AssertionError("condition not met within " + timeoutMs + "ms");
    }

    @FunctionalInterface
    public interface Condition {
        boolean check();
    }
}
