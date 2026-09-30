package com.github.guoyyit.compactblockrelayjava.tests;

import com.github.guoyyit.compactblockrelayjava.relay.config.RelayConfig;
import com.github.guoyyit.compactblockrelayjava.relay.model.Block;
import com.github.guoyyit.compactblockrelayjava.relay.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.relay.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.relay.node.Node;
import com.github.guoyyit.compactblockrelayjava.relay.store.BlockStateStore;
import com.github.guoyyit.compactblockrelayjava.relay.store.FileBlockStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：进程崩溃重启后，已确认接收的区块与未完成的紧凑重建必须可区分，
 * 未完成重建不得被误判为已接收；重启后重复通知不得触发重复处理。
 */
class RecoveryTest {

    @Test
    void confirmedBlockSurvivesRestart(@TempDir Path tempDir) {
        Path dir = tempDir.resolve("node");
        RelayConfig config = new RelayConfig();
        Block block = RelayTestFixtures.block(0, null, RelayTestFixtures.txs(3));

        Node first = new Node("node", new LoopbackNetwork(0, 0.0),
                new FileBlockStateStore(dir), config);
        first.start();
        first.mineAndAnnounce(block);
        assertEquals(block.getHeader().getBlockHash(), first.tipHash());
        first.shutdown();

        BlockStateStore reopened = new FileBlockStateStore(dir);
        assertTrue(reopened.isConfirmed(block.getHeader().getBlockHash()),
                "confirmed block must survive restart");
        assertEquals(0, reopened.loadPending().size(),
                "applied block must not remain in pending set");
    }

    @Test
    void unfinishedRebuildIsPendingAndNotConfirmedAfterRestart(@TempDir Path tempDir) {
        Path dir = tempDir.resolve("receiver");
        RelayConfig config = new RelayConfig();
        String hash = "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234";

        BlockStateStore store = new FileBlockStateStore(dir);
        store.savePending(hash, 9, "prevprevprevprevprevprevprevprevprevprevprevprevprevpr");

        BlockStateStore reopened = new FileBlockStateStore(dir);
        assertFalse(reopened.isConfirmed(hash),
                "unfinished rebuild must never be mistaken for confirmed");
        List<BlockStateStore.PendingBlock> pending = reopened.loadPending();
        assertEquals(1, pending.size());
        assertEquals(hash, pending.get(0).blockHash());
        assertEquals(9, pending.get(0).height());
    }

    @Test
    void duplicateAnnouncementAfterRestartDoesNotReapply(@TempDir Path tempDir)
            throws Exception {
        Path sourceDir = tempDir.resolve("source");
        Path receiverDir = tempDir.resolve("receiver");

        RelayConfig config = new RelayConfig();
        config.setRequestTimeoutMillis(500);
        config.setRebuildTimeoutMillis(2_000);

        Block block = RelayTestFixtures.block(0, null, RelayTestFixtures.txs(4));

        LoopbackNetwork network1 = new LoopbackNetwork(1, 0.0);
        Node source1 = new Node("source", network1, new FileBlockStateStore(sourceDir), config);
        Node receiver1 = new Node("receiver", network1, new FileBlockStateStore(receiverDir), config);
        source1.start();
        receiver1.start();
        source1.mineAndAnnounce(block);
        String hash = block.getHeader().getBlockHash();
        assertTrue(receiver1.waitUntil(() -> hash.equals(receiver1.tipHash()), 3_000));
        network1.shutdown();
        source1.shutdown();
        receiver1.shutdown();

        // “重启”：同一数据目录构造全新节点实例
        LoopbackNetwork network2 = new LoopbackNetwork(1, 0.0);
        Node source2 = new Node("source", network2, new FileBlockStateStore(sourceDir), config);
        Node receiver2 = new Node("receiver", network2, new FileBlockStateStore(receiverDir), config);
        source2.start();
        receiver2.start();

        // 重启后收到同一区块的重复通知
        source2.reannounceTo(block, "receiver");
        Thread.sleep(300);

        assertEquals(hash, receiver2.tipHash());
        Block applied = new FileBlockStateStore(receiverDir).getConfirmed(hash);
        assertEquals(1L, countConfirmedOccurrences(receiverDir),
                "duplicate announcement after restart must not re-apply the block");
        network2.shutdown();
        source2.shutdown();
        receiver2.shutdown();
    }

    private static long countConfirmedOccurrences(Path dir) throws Exception {
        // 日志中该区块的 C 记录只应出现一次（幂等 markConfirmed）
        Path log = dir.resolve("relay.log");
        try (var lines = java.nio.file.Files.lines(log)) {
            return lines.filter(l -> l.startsWith("C ")).count();
        }
    }
}
