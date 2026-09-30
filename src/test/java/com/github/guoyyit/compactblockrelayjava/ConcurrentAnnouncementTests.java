package com.github.guoyyit.compactblockrelayjava;

import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.relay.RelayConfig;
import com.github.guoyyit.compactblockrelayjava.relay.RelayNode;
import com.github.guoyyit.compactblockrelayjava.storage.FileBlockRepository;
import com.github.guoyyit.compactblockrelayjava.support.StubPeer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.announce;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.block;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.txs;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentAnnouncementTests {
    private LoopbackNetwork network;
    private RelayNode receiver;
    private RelayNode miner;

    @AfterEach
    void tearDown() {
        if (miner != null) {
            miner.shutdown();
        }
        if (receiver != null) {
            receiver.shutdown();
        }
        if (network != null) {
            network.shutdown();
        }
    }

    @Test
    void duplicateConcurrentAnnouncementsTriggerSingleRequestAndSingleAccept(@TempDir Path dir)
            throws Exception {
        network = new LoopbackNetwork();
        List<Transaction> blockTxs = txs("tx-a", "tx-b");
        Block fullBlock = block("b1", "g", 0, blockTxs);
        StubPeer stub1 = new StubPeer("stub1", network, fullBlock);
        StubPeer stub2 = new StubPeer("stub2", network, fullBlock);
        receiver = new RelayNode("receiver", RelayConfig.defaults(), network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        network.register("receiver", receiver);
        network.register("stub1", stub1);
        network.register("stub2", stub2);
        receiver.connectPeer("stub1");
        receiver.connectPeer("stub2");
        receiver.start();
        receiver.submitTransaction(blockTxs.get(0));

        // 两份通知并发到达，使用不同 SipHash 密钥，接收方仍只能接受一次区块。
        network.send(announce("stub1", "receiver", fullBlock, 11L, 22L));
        network.send(announce("stub2", "receiver", fullBlock, 33L, 44L));

        waitFor(3000, () -> receiver.hasBlock("b1"));
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
        int totalTxRequests = stub1.txRequests.size() + stub2.txRequests.size();
        assertThat(totalTxRequests).isEqualTo(1);

        // 重复/迟到的通知被丢弃，不触发二次处理。
        network.send(announce("stub1", "receiver", fullBlock, 55L, 66L));
        Thread.sleep(300);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
        assertThat(receiver.stats().duplicateAnnouncementsDropped()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void staleAnnouncementIsSafelyDropped(@TempDir Path dir) throws Exception {
        network = new LoopbackNetwork();
        miner = new RelayNode("miner", RelayConfig.defaults(), network,
                new FileBlockRepository(dir.resolve("miner.blocks")));
        List<Transaction> firstTxs = txs("genesis-tx");
        List<Transaction> staleTxs = txs("stale-tx");
        Block genesis = block("b0", "g", 0, firstTxs);
        Block next = block("b1", "b0", 1, txs("next-tx"));
        Block stale = block("old", "g", 0, staleTxs);
        receiver = new RelayNode("receiver", RelayConfig.defaults(), network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        network.register("miner", miner);
        network.register("receiver", receiver);
        miner.connectPeer("receiver");
        receiver.connectPeer("miner");
        miner.start();
        receiver.start();

        miner.announceBlock(genesis);
        waitFor(3000, () -> receiver.hasBlock("b0"));
        receiver.submitTransaction(next.transactions().get(0));
        miner.announceBlock(next);
        waitFor(3000, () -> receiver.hasBlock("b1"));

        network.send(announce("miner", "receiver", stale, 5L, 6L));
        Thread.sleep(300);
        assertThat(receiver.hasBlock("old")).isFalse();
        assertThat(receiver.stats().staleAnnouncementsDropped()).isGreaterThanOrEqualTo(1);
    }
}
