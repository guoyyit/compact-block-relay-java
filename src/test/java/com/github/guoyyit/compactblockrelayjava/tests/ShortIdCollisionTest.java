package com.github.guoyyit.compactblockrelayjava.tests;

import com.github.guoyyit.compactblockrelayjava.relay.config.RelayConfig;
import com.github.guoyyit.compactblockrelayjava.relay.model.Block;
import com.github.guoyyit.compactblockrelayjava.relay.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.relay.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.relay.node.Node;
import com.github.guoyyit.compactblockrelayjava.relay.protocol.FullBlockRequest;
import com.github.guoyyit.compactblockrelayjava.relay.store.FileBlockStateStore;
import com.github.guoyyit.compactblockrelayjava.relay.util.Hashes;
import com.github.guoyyit.compactblockrelayjava.relay.util.ShortTxIdCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：短标识发生碰撞时，接收方绝不能接受交易内容错误的区块，
 * 必须回退到完整区块传输，且最终上链内容与区块头声明逐笔一致。
 */
class ShortIdCollisionTest {

    @Test
    void collisionNeverAcceptsWrongContentAndFallsBackToFullBlock(@TempDir Path tempDir)
            throws Exception {
        LoopbackNetwork network = new LoopbackNetwork(2, 0.0);
        RelayConfig config = new RelayConfig();
        config.setShortTxIdBytes(1);
        config.setRequestTimeoutMillis(500);
        config.setRebuildTimeoutMillis(2_000);

        long nonce = 0xCAFEBABEL;
        Node miner = new Node("miner", network,
                new FileBlockStateStore(tempDir.resolve("miner")), config,
                () -> nonce);
        Node receiver = new Node("receiver", network,
                new FileBlockStateStore(tempDir.resolve("receiver")), config);
        miner.start();
        receiver.start();

        ShortTxIdCalculator calculator = new ShortTxIdCalculator(1);

        Transaction txA = RelayTestFixtures.tx("alpha");
        // 暴力找到另一笔 txB，使其一字节短标识与 txA 碰撞，但完整标识不同
        Transaction txB = null;
        byte[] target = calculator.compute(txA.getTxId(), nonce);
        for (long i = 0; i < 200_000L && txB == null; i++) {
            byte[] payload = ("collide-" + i).getBytes(StandardCharsets.UTF_8);
            String id = Hashes.toHex(Hashes.sha256(payload));
            if (!id.equals(txA.getTxId()) && Arrays.equals(calculator.compute(id, nonce), target)) {
                txB = new Transaction(id, payload);
            }
        }
        assertTrue(txB != null, "test must find a 1-byte short id collision");

        // 真实区块里只有 txA
        List<Transaction> blockTxs = List.of(txA);
        Block block = RelayTestFixtures.block(0, null, blockTxs);
        // 接收方池中没有真实的 txA，但持有碰撞的 txB（其短标识与区块声明的短标识相同）
        receiver.receiveTransaction(txB);

        // 广播方正常出块；接收方只持有碰撞交易 txB，其短标识索引会把该槽位误配为 txB。
        // 逐笔完整标识校验必须发现 txB != 区块头声明的 txA，并回退到完整区块。
        miner.mineAndAnnounce(block);

        String hash = block.getHeader().getBlockHash();
        assertTrue(receiver.waitUntil(() -> hash.equals(receiver.tipHash()), 3_000),
                "collision must be safely resolved (via full-block fallback) and block applied");

        assertTrue(network.sentCount(FullBlockRequest.class, "miner") >= 1L,
                "ambiguous collision should trigger a full block request");

        // 上链区块的交易必须是区块头声明的 txA，而不是碰撞的 txB
        assertEquals(List.of(txA.getTxId()), receiver.tipTxIds());

        network.shutdown();
        miner.shutdown();
        receiver.shutdown();
    }
}
