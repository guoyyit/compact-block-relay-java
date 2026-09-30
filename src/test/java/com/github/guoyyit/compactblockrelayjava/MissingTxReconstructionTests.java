package com.github.guoyyit.compactblockrelayjava;

import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.relay.NodeStats;
import com.github.guoyyit.compactblockrelayjava.relay.RelayConfig;
import com.github.guoyyit.compactblockrelayjava.relay.RelayNode;
import com.github.guoyyit.compactblockrelayjava.storage.FileBlockRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.block;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.txs;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

class MissingTxReconstructionTests {
    private LoopbackNetwork network;
    private RelayNode miner;
    private RelayNode receiver;

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

    private void startNodes(@TempDir Path dir, RelayConfig config) {
        network = new LoopbackNetwork();
        miner = new RelayNode("miner", config, network,
                new FileBlockRepository(dir.resolve("miner.blocks")));
        receiver = new RelayNode("receiver", config, network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        network.register("miner", miner);
        network.register("receiver", receiver);
        miner.connectPeer("receiver");
        receiver.connectPeer("miner");
        miner.start();
        receiver.start();
    }

    @Test
    void rebuildsFromMempoolWhenAllTransactionsPresent(@TempDir Path dir) throws Exception {
        startNodes(dir, RelayConfig.defaults());
        List<Transaction> transactions = txs("tx-a", "tx-b", "tx-c");
        Block block = block("b1", "g", 0, transactions);
        for (Transaction tx : transactions) {
            receiver.submitTransaction(tx);
        }

        miner.announceBlock(block);

        waitFor(3000, () -> receiver.hasBlock("b1"));
        assertThat(receiver.stats().compactRebuilt()).isEqualTo(1);
        assertThat(receiver.stats().txRequests()).isZero();
        assertThat(receiver.stats().fullBlockFallbacks()).isZero();
        assertThat(receiver.chainHeight()).isEqualTo(0);
    }

    @Test
    void missingTransactionsAreRequestedInOneBatchAndReconstructed(@TempDir Path dir) throws Exception {
        startNodes(dir, RelayConfig.defaults());
        List<Transaction> transactions = txs("tx-a", "tx-b", "tx-c", "tx-d");
        Block block = block("b1", "g", 0, transactions);
        receiver.submitTransaction(transactions.get(1));

        miner.announceBlock(block);

        waitFor(3000, () -> receiver.hasBlock("b1"));
        NodeStats stats = receiver.stats();
        assertThat(stats.compactRebuilt()).isEqualTo(1);
        assertThat(stats.txRequests()).isEqualTo(1);
        assertThat(stats.fullBlockFallbacks()).isZero();
        assertThat(receiver.mempoolSize()).isZero();
    }
}
