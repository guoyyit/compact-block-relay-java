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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：接收方只持有部分交易，缺失交易必须通过一次合并请求补齐并成功上链。
 */
class MissingTxRecoveryTest {

    @Test
    void missingTransactionsAreRequestedInOneBatchAndBlockApplied(@TempDir Path tempDir)
            throws Exception {
        LoopbackNetwork network = new LoopbackNetwork(5, 0.0);
        RelayConfig config = new RelayConfig();
        config.setRequestTimeoutMillis(500);
        config.setRebuildTimeoutMillis(2_000);

        Node miner = new Node("miner", network,
                new FileBlockStateStore(tempDir.resolve("miner")), config);
        Node receiver = new Node("receiver", network,
                new FileBlockStateStore(tempDir.resolve("receiver")), config);
        miner.start();
        receiver.start();

        List<Transaction> transactions = RelayTestFixtures.txs(8);
        Block block = RelayTestFixtures.block(0, null, transactions);

        // 接收方交易池只含前 3 笔，后 5 笔缺失
        for (int i = 0; i < 3; i++) {
            receiver.receiveTransaction(transactions.get(i));
        }

        miner.mineAndAnnounce(block);

        String expectedHash = block.getHeader().getBlockHash();
        assertTrue(receiver.waitUntil(() -> expectedHash.equals(receiver.tipHash()), 3_000),
                "receiver should rebuild and apply the block after batched txn recovery");
        assertEquals(1L, network.sentCount(TxnRequest.class, "miner"),
                "all missing transactions must be merged into a single request");
        assertEquals(0, receiver.getMempool().size(),
                "all block transactions should be removed from mempool after apply");

        network.shutdown();
        miner.shutdown();
        receiver.shutdown();
    }
}
