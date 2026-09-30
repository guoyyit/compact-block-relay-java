package com.github.guoyyit.compactblockrelayjava.tests;

import com.github.guoyyit.compactblockrelayjava.relay.config.RelayConfig;
import com.github.guoyyit.compactblockrelayjava.relay.model.Block;
import com.github.guoyyit.compactblockrelayjava.relay.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.relay.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.relay.node.Node;
import com.github.guoyyit.compactblockrelayjava.relay.protocol.FullBlockRequest;
import com.github.guoyyit.compactblockrelayjava.relay.protocol.TxnRequest;
import com.github.guoyyit.compactblockrelayjava.relay.store.FileBlockStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：补齐请求失败、完整区块请求超时，均必须安全收敛。
 */
class TimeoutFallbackTest {

    @Test
    void failedTxnRequestFallsBackToFullBlockAndApplies(@TempDir Path tempDir) throws Exception {
        LoopbackNetwork network = new LoopbackNetwork(2, 0.0);
        RelayConfig config = new RelayConfig();
        config.setRequestTimeoutMillis(300);
        config.setRebuildTimeoutMillis(2_000);

        Node miner = new Node("miner", network,
                new FileBlockStateStore(tempDir.resolve("miner")), config);
        Node receiver = new Node("receiver", network,
                new FileBlockStateStore(tempDir.resolve("receiver")), config);
        miner.start();
        receiver.start();

        List<Transaction> transactions = RelayTestFixtures.txs(4);
        Block block = RelayTestFixtures.block(0, null, transactions);

        // 丢弃接收方发出的补齐请求，强制走完整区块回退
        network.addFilter(e -> e.getFrom().equals("receiver")
                && e.getPayload() instanceof TxnRequest);

        miner.mineAndAnnounce(block);
        String hash = block.getHeader().getBlockHash();
        assertTrue(receiver.waitUntil(() -> hash.equals(receiver.tipHash()), 3_000),
                "failed txn request must fall back to full block and still apply");
        assertTrue(network.sentCount(FullBlockRequest.class, "miner") >= 1L,
                "fallback must issue a full block request");
        assertEquals(block.getHeader().getTxIds(), receiver.tipTxIds());

        network.shutdown();
        miner.shutdown();
        receiver.shutdown();
    }

    @Test
    void fullBlockTimeoutDiscardsPendingWithoutApplying(@TempDir Path tempDir) throws Exception {
        LoopbackNetwork network = new LoopbackNetwork(2, 0.0);
        RelayConfig config = new RelayConfig();
        config.setRequestTimeoutMillis(200);
        config.setRebuildTimeoutMillis(2_000);
        config.setFullBlockRetry(0);

        Node miner = new Node("miner", network,
                new FileBlockStateStore(tempDir.resolve("miner")), config);
        Node receiver = new Node("receiver", network,
                new FileBlockStateStore(tempDir.resolve("receiver")), config);
        miner.start();
        receiver.start();

        List<Transaction> transactions = RelayTestFixtures.txs(3);
        Block block = RelayTestFixtures.block(0, null, transactions);

        // 所有从 receiver 发出的请求都失败（既补不齐交易，也拿不到完整区块）
        network.addFilter(e -> e.getFrom().equals("receiver")
                && (e.getPayload() instanceof TxnRequest
                || e.getPayload() instanceof FullBlockRequest));

        miner.mineAndAnnounce(block);
        String hash = block.getHeader().getBlockHash();

        // 等待补齐请求超时（300ms）+ 完整区块请求超时（200ms）后仍未上链
        Thread.sleep(900);
        assertNull(receiver.tipHash(),
                "block must not be applied when all recovery paths fail");

        // 恢复网络后，对端重发通知必须能重新处理并最终上链（未完成重建不阻塞重发）
        network.clearFilters();
        miner.reannounceTo(block, "receiver");
        assertTrue(receiver.waitUntil(() -> hash.equals(receiver.tipHash()), 3_000),
                "repeated announcement after recovery should rebuild and apply");

        network.shutdown();
        miner.shutdown();
        receiver.shutdown();
    }
}
