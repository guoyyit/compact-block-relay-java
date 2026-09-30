package com.github.guoyyit.compactblockrelayjava.tests;

import com.github.guoyyit.compactblockrelayjava.relay.config.RelayConfig;
import com.github.guoyyit.compactblockrelayjava.relay.model.Block;
import com.github.guoyyit.compactblockrelayjava.relay.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.relay.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.relay.node.Node;
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
 * 场景：已处理区块通知、过期（高度更低）通知、孤儿通知必须安全丢弃；
 * 去重记录与交易池增长都必须有上界。
 */
class AnnouncementDedupTest {

    @Test
    void processedAndStaleAnnouncementsAreDroppedWithoutRequests(@TempDir Path tempDir)
            throws Exception {
        LoopbackNetwork network = new LoopbackNetwork(1, 0.0);
        RelayConfig config = new RelayConfig();
        config.setRequestTimeoutMillis(300);
        config.setRebuildTimeoutMillis(1_000);

        Node source = new Node("source", network,
                new FileBlockStateStore(tempDir.resolve("source")), config);
        Node receiver = new Node("receiver", network,
                new FileBlockStateStore(tempDir.resolve("receiver")), config);
        source.start();
        receiver.start();

        Block block0 = RelayTestFixtures.block(0, null, RelayTestFixtures.txs(2));
        source.mineAndAnnounce(block0);
        String hash0 = block0.getHeader().getBlockHash();
        assertTrue(receiver.waitUntil(() -> hash0.equals(receiver.tipHash()), 3_000));

        // 已处理区块的重复通知：丢弃，不产生新请求
        long requestsBefore = network.sentCount(TxnRequest.class, "source");
        source.reannounceTo(block0, "receiver");
        Thread.sleep(200);
        assertEquals(requestsBefore, network.sentCount(TxnRequest.class, "source"),
                "already-processed announcement must not trigger a new request");

        // 过期通知：高度低于当前链尖，安全丢弃
        Block staleBlock = RelayTestFixtures.block(0, null, RelayTestFixtures.txs(2));
        source.reannounceTo(staleBlock, "receiver");
        Thread.sleep(200);
        assertEquals(hash0, receiver.tipHash(), "stale announcement must not change the tip");

        network.shutdown();
        source.shutdown();
        receiver.shutdown();
    }

    @Test
    void orphanAnnouncementIsHeldBoundedAndDoesNotApply(@TempDir Path tempDir) throws Exception {
        LoopbackNetwork network = new LoopbackNetwork(1, 0.0);
        RelayConfig config = new RelayConfig();
        config.setOrphanMaxSize(4);
        config.setRequestTimeoutMillis(200);
        config.setRebuildTimeoutMillis(500);

        Node source = new Node("source", network,
                new FileBlockStateStore(tempDir.resolve("source")), config);
        Node receiver = new Node("receiver", network,
                new FileBlockStateStore(tempDir.resolve("receiver")), config);
        source.start();
        receiver.start();

        // 高度为 3 的区块，但其父链不存在：孤儿通知不得上链
        String unknownParent = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
        Block orphan = RelayTestFixtures.block(3, unknownParent, RelayTestFixtures.txs(2));
        source.mineAndAnnounce(orphan);
        Thread.sleep(400);
        assertNull(receiver.tipHash(), "orphan block must not be applied");

        network.shutdown();
        source.shutdown();
        receiver.shutdown();
    }

    @Test
    void mempoolGrowthIsBounded(@TempDir Path tempDir) {
        RelayConfig config = new RelayConfig();
        config.setMempoolMaxSize(10);
        Node node = new Node("n", new LoopbackNetwork(0, 0.0),
                new FileBlockStateStore(tempDir.resolve("n")), config);

        for (int i = 0; i < 100; i++) {
            node.receiveTransaction(RelayTestFixtures.tx("bulk-" + i));
        }
        assertEquals(10, node.getMempool().size(),
                "mempool must not grow beyond its configured upper bound");
        node.shutdown();
    }

    @Test
    void seenAnnouncementRecordsAreBounded(@TempDir Path tempDir) throws Exception {
        LoopbackNetwork network = new LoopbackNetwork(0, 0.0);
        RelayConfig config = new RelayConfig();
        config.setSeenAnnouncementMaxSize(8);
        config.setRebuildTimeoutMillis(5_000);
        config.setRequestTimeoutMillis(5_000);

        Node source = new Node("source", network,
                new FileBlockStateStore(tempDir.resolve("source")), config);
        Node receiver = new Node("receiver", network,
                new FileBlockStateStore(tempDir.resolve("receiver")), config);
        source.start();
        receiver.start();

        // 发送大量不同的高高度孤儿通知；去重记录不得无上界增长。
        for (int i = 100; i < 300; i++) {
            String parent = String.format("%064d", i + 1L);
            Block orphan = RelayTestFixtures.block(i, parent, RelayTestFixtures.txs(1));
            source.reannounceTo(orphan, "receiver");
        }
        Thread.sleep(200);
        assertTrue(receiver.seenAnnouncementCount() <= 8,
                "seen-announcement dedup records must stay bounded, actual="
                        + receiver.seenAnnouncementCount());

        network.shutdown();
        source.shutdown();
        receiver.shutdown();
    }
}
