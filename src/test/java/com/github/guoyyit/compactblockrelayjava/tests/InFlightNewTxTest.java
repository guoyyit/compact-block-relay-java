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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：重建进行期间，缺失交易恰好在补齐响应之外通过“新交易”途径到达，
 * 重建必须利用该交易收敛，且状态一致、不重复处理。
 */
class InFlightNewTxTest {

    @Test
    void newTxArrivingDuringRebuildCompletesWithoutFallback(@TempDir Path tempDir)
            throws Exception {
        LoopbackNetwork network = new LoopbackNetwork(10, 0.0);
        RelayConfig config = new RelayConfig();
        config.setRequestTimeoutMillis(2_000);
        config.setRebuildTimeoutMillis(5_000);

        Node miner = new Node("miner", network,
                new FileBlockStateStore(tempDir.resolve("miner")), config);
        Node receiver = new Node("receiver", network,
                new FileBlockStateStore(tempDir.resolve("receiver")), config);
        miner.start();
        receiver.start();

        List<Transaction> transactions = RelayTestFixtures.txs(3);
        Block block = RelayTestFixtures.block(0, null, transactions);

        // 接收方只持有第一笔
        receiver.receiveTransaction(transactions.get(0));

        // 直接发送通知但丢弃补齐请求，模拟“只能靠新交易到达”来收敛
        network.addFilter(e -> e.getFrom().equals("receiver"));
        miner.mineAndAnnounce(block);

        // 在重建进行期间，把缺失交易当作“新收到的交易”喂给接收方
        Thread.sleep(30);
        receiver.receiveTransaction(transactions.get(1));
        receiver.receiveTransaction(transactions.get(2));

        String hash = block.getHeader().getBlockHash();
        assertTrue(receiver.waitUntil(() -> hash.equals(receiver.tipHash()), 3_000),
                "new transactions arriving mid-rebuild must converge the block");
        assertEquals(block.getHeader().getTxIds(), receiver.tipTxIds());

        network.shutdown();
        miner.shutdown();
        receiver.shutdown();
    }
}
