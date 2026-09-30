package com.github.guoyyit.compactblockrelayjava.tests;

import com.github.guoyyit.compactblockrelayjava.relay.config.RelayConfig;
import com.github.guoyyit.compactblockrelayjava.relay.model.Block;
import com.github.guoyyit.compactblockrelayjava.relay.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.relay.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.relay.node.Node;
import com.github.guoyyit.compactblockrelayjava.relay.store.FileBlockStateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：先收到子区块通知（父块缺失，成为孤儿），父块随后到达，
 * 孤儿必须被安全保留并在父块上链后自动重放、最终子块也上链。
 */
class OrphanReconnectTest {

    @Test
    void orphanReplaysAfterParentArrives(@TempDir Path tempDir) throws Exception {
        LoopbackNetwork network = new LoopbackNetwork(1, 0.0);
        RelayConfig config = new RelayConfig();
        config.setOrphanMaxSize(64);
        config.setRequestTimeoutMillis(500);
        config.setRebuildTimeoutMillis(2_000);

        Node source = new Node("source", network,
                new FileBlockStateStore(tempDir.resolve("source")), config);
        Node receiver = new Node("receiver", network,
                new FileBlockStateStore(tempDir.resolve("receiver")), config);
        source.start();
        receiver.start();

        Block parent = RelayTestFixtures.block(0, null, RelayTestFixtures.txs("parent", 2));
        Block child = RelayTestFixtures.block(1, parent.getHeader().getBlockHash(),
                RelayTestFixtures.txs("child", 2));

        // 用过滤器制造对接收方的乱序：先拦住父块通知
        network.addFilter(e -> e.getTo().equals("receiver")
                && e.getPayload() instanceof com.github.guoyyit.compactblockrelayjava.relay.protocol.CompactBlockAnnouncement ann
                && ann.getHeader().getBlockHash().equals(parent.getHeader().getBlockHash()));

        // 广播方先持有父子区块（本地直接上链，heldBlocks 可服务对端补齐请求）
        source.mineAndAnnounce(parent);
        source.mineAndAnnounce(child);

        // 接收方本地先有子块全部交易
        for (Transaction tx : child.getTransactions()) {
            receiver.receiveTransaction(tx);
        }

        // 显式补发子块通知（父块被过滤），子块成为孤儿
        source.reannounceTo(child, "receiver");
        Thread.sleep(200);
        assertNull(receiver.tipHash(), "child must stay orphan until parent arrives");

        // 解除过滤并补发父块；父块上链后孤儿子块自动重放
        network.clearFilters();
        source.reannounceTo(parent, "receiver");

        assertTrue(receiver.waitUntil(
                () -> child.getHeader().getBlockHash().equals(receiver.tipHash()), 3_000),
                "orphan child must replay and apply once its parent is connected");

        network.shutdown();
        source.shutdown();
        receiver.shutdown();
    }
}
