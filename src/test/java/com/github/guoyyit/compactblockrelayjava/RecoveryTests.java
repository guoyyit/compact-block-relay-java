package com.github.guoyyit.compactblockrelayjava;

import com.github.guoyyit.compactblockrelayjava.crypto.SipHash;
import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.protocol.AnnounceMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.TxResponseMessage;
import com.github.guoyyit.compactblockrelayjava.relay.RelayConfig;
import com.github.guoyyit.compactblockrelayjava.relay.RelayNode;
import com.github.guoyyit.compactblockrelayjava.storage.FileBlockRepository;
import com.github.guoyyit.compactblockrelayjava.support.StubPeer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.announce;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.block;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.txs;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

class RecoveryTests {

    @Test
    void confirmedBlockSurvivesRestartAndDuplicateAnnouncementIsDropped(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("receiver.blocks");
        List<Transaction> blockTxs = txs("tx-a", "tx-b");
        Block fullBlock = block("b1", "g", 0, blockTxs);

        LoopbackNetwork network = new LoopbackNetwork();
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        RelayNode receiver = new RelayNode("receiver", RelayConfig.defaults(), network,
                new FileBlockRepository(store));
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();
        receiver.submitTransaction(blockTxs.get(0));

        network.send(announce("stub", "receiver", fullBlock, 77L, 88L));
        waitFor(3000, () -> receiver.hasBlock("b1"));

        // 模拟进程崩溃：事件循环立即终止，未完成的重建状态只存在于内存。
        receiver.shutdown();
        network.shutdown();

        LoopbackNetwork restartedNetwork = new LoopbackNetwork();
        StubPeer restartedStub = new StubPeer("stub", restartedNetwork, fullBlock);
        RelayNode restarted = new RelayNode("receiver", RelayConfig.defaults(), restartedNetwork,
                new FileBlockRepository(store));
        restartedNetwork.register("receiver", restarted);
        restartedNetwork.register("stub", restartedStub);
        restarted.connectPeer("stub");
        restarted.start();

        waitFor(1000, () -> restarted.hasBlock("b1"));
        assertThat(restarted.chainHeight()).isEqualTo(0);

        // 重启后对端重放同一通知，不得重复处理：无补齐请求、无额外接受计数。
        restartedNetwork.send(announce("stub", "receiver", fullBlock, 99L, 11L));
        Thread.sleep(300);
        assertThat(restartedStub.txRequests).isEmpty();
        assertThat(restarted.stats().blocksAccepted()).isEqualTo(1);
        assertThat(restarted.stats().duplicateAnnouncementsDropped()).isGreaterThanOrEqualTo(1);

        restarted.shutdown();
        restartedNetwork.shutdown();
    }

    @Test
    void unfinishedReconstructionIsNotMistakenForAcceptedAfterCrash(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("receiver.blocks");
        List<Transaction> blockTxs = txs("missing-tx");
        Block fullBlock = block("b1", "g", 0, blockTxs);

        LoopbackNetwork network = new LoopbackNetwork();
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        RelayConfig config = new RelayConfig(8, 5000, 5000, 100, 64, 16, 0);
        RelayNode receiver = new RelayNode("receiver", config, network,
                new FileBlockRepository(store));
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();

        // 补齐响应全部丢弃，重建停在“等待缺失交易”状态。
        network.setDropRule(message -> message instanceof TxResponseMessage);
        long k0 = 31L;
        long k1 = 41L;
        List<Long> shortIds = blockTxs.stream()
                .map(tx -> SipHash.shortId(k0, k1, tx.txid())).toList();
        network.send(new AnnounceMessage("stub", "receiver", fullBlock.header(), shortIds, k0, k1));

        waitFor(1000, () -> !stub.txRequests.isEmpty());
        assertThat(stub.txRequests).hasSize(1);
        // 崩溃发生在重建完成之前。
        receiver.shutdown();

        // 磁盘上不得存在该区块，且日志中没有任何 H 记录。
        if (Files.exists(store)) {
            String contents = Files.readString(store);
            assertThat(contents).doesNotContain("H b1 ");
        }

        // 重启后节点不知道 b1：重新通知会再次发起重建，而不是当作已接收。
        network.shutdown();
        LoopbackNetwork restartedNetwork = new LoopbackNetwork();
        StubPeer restartedStub = new StubPeer("stub", restartedNetwork, fullBlock);
        RelayNode restarted = new RelayNode("receiver", RelayConfig.defaults(), restartedNetwork,
                new FileBlockRepository(store));
        restartedNetwork.register("receiver", restarted);
        restartedNetwork.register("stub", restartedStub);
        restarted.connectPeer("stub");
        restarted.start();

        restartedNetwork.send(announce("stub", "receiver", fullBlock, 31L, 41L));
        waitFor(3000, () -> restarted.hasBlock("b1"));
        assertThat(restarted.stats().blocksAccepted()).isEqualTo(1);

        restarted.shutdown();
        restartedNetwork.shutdown();
    }

    @Test
    void tornTrailingLogRecordFromCrashIsDiscardedOnReplay(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("receiver.blocks");
        List<Transaction> blockTxs = txs("tx-a");
        Block fullBlock = block("b1", "g", 0, blockTxs);

        LoopbackNetwork network = new LoopbackNetwork();
        RelayNode receiver = new RelayNode("receiver", RelayConfig.defaults(), network,
                new FileBlockRepository(store));
        receiver.start();
        receiver.announceBlock(fullBlock);
        waitFor(1000, () -> receiver.hasBlock("b1"));
        receiver.shutdown();

        // 模拟崩溃时只写入了下一区块的半条记录。
        Files.writeString(store, "\nH b2 b1 1 55 3\nX partial",
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        LoopbackNetwork network2 = new LoopbackNetwork();
        RelayNode restarted = new RelayNode("receiver", RelayConfig.defaults(), network2,
                new FileBlockRepository(store));
        restarted.start();
        assertThat(restarted.hasBlock("b1")).isTrue();
        assertThat(restarted.hasBlock("b2")).isFalse();
        restarted.shutdown();
        network2.shutdown();
        network.shutdown();
    }
}
