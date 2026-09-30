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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：同一区块的多个通知并发到达接收方，状态必须收敛，
 * 不得重复请求、重复处理或丢失区块。
 */
class ConcurrentAnnouncementTest {

    @Test
    void concurrentDuplicateAnnouncementsConvergeToSingleApply(@TempDir Path tempDir)
            throws Exception {
        LoopbackNetwork network = new LoopbackNetwork(1, 0.0);
        RelayConfig config = new RelayConfig();
        config.setRequestTimeoutMillis(500);
        config.setRebuildTimeoutMillis(2_000);

        Node source = new Node("source", network,
                new FileBlockStateStore(tempDir.resolve("source")), config);
        Node receiver = new Node("receiver", network,
                new FileBlockStateStore(tempDir.resolve("receiver")), config);
        source.start();
        receiver.start();

        List<Transaction> transactions = RelayTestFixtures.txs(5);
        Block block = RelayTestFixtures.block(0, null, transactions);
        source.mineAndAnnounce(block);

        // 用多个线程并发向 receiver 重发同一区块通知
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    source.reannounceTo(block, "receiver");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(2, TimeUnit.SECONDS));
        pool.shutdown();

        String hash = block.getHeader().getBlockHash();
        assertTrue(receiver.waitUntil(() -> hash.equals(receiver.tipHash()), 3_000),
                "concurrent announcements must converge to one applied block");

        // 只允许一次合并补齐请求（每次重建会话最多一次 TxnRequest）
        assertTrue(network.sentCount(TxnRequest.class, "source") <= 1L,
                "concurrent duplicate announcements must not cause duplicate requests");
        assertEquals(block.getHeader().getTxIds(), receiver.tipTxIds());

        network.shutdown();
        source.shutdown();
        receiver.shutdown();
    }
}
