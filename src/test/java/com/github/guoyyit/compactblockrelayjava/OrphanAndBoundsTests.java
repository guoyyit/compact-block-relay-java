package com.github.guoyyit.compactblockrelayjava;

import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.relay.RelayConfig;
import com.github.guoyyit.compactblockrelayjava.relay.RelayNode;
import com.github.guoyyit.compactblockrelayjava.storage.FileBlockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.block;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.tx;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.txs;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

class OrphanAndBoundsTests {

    @Test
    void orphanAnnouncementIsHeldThenProcessedWhenParentConnects(@TempDir Path dir) throws Exception {
        LoopbackNetwork network = new LoopbackNetwork();
        RelayNode miner = new RelayNode("miner", RelayConfig.defaults(), network,
                new FileBlockRepository(dir.resolve("miner.blocks")));
        RelayNode receiver = new RelayNode("receiver", RelayConfig.defaults(), network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        Block genesis = block("b0", "g", 0, txs("g-tx"));
        Block middle = block("b1", "b0", 1, txs("m-tx"));
        Block next = block("b2", "b1", 2, txs("n-tx"));
        network.register("miner", miner);
        network.register("receiver", receiver);
        miner.connectPeer("receiver");
        receiver.connectPeer("miner");
        miner.start();
        receiver.start();

        // 先接收 b0 形成链尖，再让 b2（父块 b1 缺失）先到，成为孤儿通知。
        miner.announceBlock(genesis);
        waitFor(3000, () -> receiver.hasBlock("b0"));
        receiver.submitTransaction(next.transactions().get(0));
        com.github.guoyyit.compactblockrelayjava.protocol.Message orphan =
                com.github.guoyyit.compactblockrelayjava.support.TestFixtures
                        .announce("miner", "receiver", next, 101L, 202L);
        network.send(orphan);
        waitFor(1000, () -> receiver.stats().orphansHeld() >= 1);
        assertThat(receiver.hasBlock("b2")).isFalse();

        // 父块到达后孤儿自动重放，不丢失区块。
        receiver.submitTransaction(middle.transactions().get(0));
        miner.announceBlock(middle);
        waitFor(3000, () -> receiver.hasBlock("b1"));
        waitFor(3000, () -> receiver.hasBlock("b2"));
        assertThat(receiver.chainHeight()).isEqualTo(2);

        miner.shutdown();
        receiver.shutdown();
        network.shutdown();
    }

    @Test
    void mempoolGrowthIsBoundedByFifoEviction(@TempDir Path dir) {
        LoopbackNetwork network = new LoopbackNetwork();
        RelayConfig config = new RelayConfig(8, 2000, 500, 3, 64, 16, 1);
        RelayNode node = new RelayNode("small", config, network,
                new FileBlockRepository(dir.resolve("small.blocks")));
        node.start();
        for (int i = 0; i < 10; i++) {
            node.submitTransaction(tx("tx-" + i));
        }
        assertThat(node.mempoolSize()).isEqualTo(3);
        node.shutdown();
        network.shutdown();
    }

    @Test
    void seenHashDedupWindowIsBounded(@TempDir Path dir) throws Exception {
        LoopbackNetwork network = new LoopbackNetwork();
        RelayConfig config = new RelayConfig(8, 2000, 500, 100, 3, 16, 1);
        RelayNode miner = new RelayNode("miner", config, network,
                new FileBlockRepository(dir.resolve("miner.blocks")));
        RelayNode receiver = new RelayNode("receiver", config, network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        network.register("miner", miner);
        network.register("receiver", receiver);
        miner.connectPeer("receiver");
        receiver.connectPeer("miner");
        miner.start();
        receiver.start();

        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String prev = i == 0 ? "g" : ("b" + (i - 1));
            Block block = block("b" + i, prev, i, txs("tx-" + i));
            blocks.add(block);
            miner.announceBlock(block);
        }
        waitFor(3000, () -> receiver.chainHeight() == 4);

        // 去重窗口仅保留最近 3 个哈希，最早区块哈希已滚出窗口；但已接收区块仍被链状态拦截。
        Block oldest = blocks.get(0);
        com.github.guoyyit.compactblockrelayjava.protocol.Message replay =
                com.github.guoyyit.compactblockrelayjava.support.TestFixtures
                        .announce("miner", "receiver", oldest, 9L, 10L);
        network.send(replay);
        Thread.sleep(200);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(5);

        miner.shutdown();
        receiver.shutdown();
        network.shutdown();
    }
}
