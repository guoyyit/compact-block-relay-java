package com.github.guoyyit.compactblockrelayjava;

import com.github.guoyyit.compactblockrelayjava.crypto.SipHash;
import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.pool.Mempool;
import com.github.guoyyit.compactblockrelayjava.protocol.AnnounceMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.TxResponseMessage;
import com.github.guoyyit.compactblockrelayjava.relay.RelayConfig;
import com.github.guoyyit.compactblockrelayjava.relay.RelayNode;
import com.github.guoyyit.compactblockrelayjava.storage.FileBlockRepository;
import com.github.guoyyit.compactblockrelayjava.support.StubPeer;
import com.github.guoyyit.compactblockrelayjava.support.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.block;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.txs;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重建进行中本地新到达交易的回填行为。
 */
class InFlightTxBackfillTests {
    private LoopbackNetwork network;
    private RelayNode receiver;

    @AfterEach
    void tearDown() {
        if (receiver != null) {
            receiver.shutdown();
        }
        if (network != null) {
            network.shutdown();
        }
    }

    private RelayConfig slowTxTimeoutConfig() {
        // 重建窗口与补齐超时都拉长，保证新交易在任意响应/超时之前到达。
        return new RelayConfig(8, 5_000, 5_000, 100, 64, 16, 1);
    }

    @Test
    void lateLocalTransactionCompletesRebuildWithoutWaitingForResponse(@TempDir Path dir)
            throws Exception {
        network = new LoopbackNetwork();
        List<Transaction> blockTxs = txs("tx-a", "tx-b", "tx-c");
        Block fullBlock = block("b1", "g", 0, blockTxs);
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        receiver = new RelayNode("receiver", slowTxTimeoutConfig(), network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();

        // 补齐响应全部丢弃：节点只能依赖本地新交易完成重建。
        network.setDropRule(message -> message instanceof TxResponseMessage);
        // 下标 1 的交易已在本地池，首批补齐请求只针对下标 0、2。
        receiver.submitTransaction(blockTxs.get(1));
        network.send(com.github.guoyyit.compactblockrelayjava.support.TestFixtures
                .announce("stub", "receiver", fullBlock, 401L, 501L));
        waitFor(1000, () -> !stub.txRequests.isEmpty());
        assertThat(stub.txRequests).hasSize(1);

        // 在补齐请求在途期间，两笔缺失交易陆续到达本地。
        receiver.submitTransaction(blockTxs.get(0));
        receiver.submitTransaction(blockTxs.get(2));

        waitFor(2000, () -> receiver.hasBlock("b1"));
        assertThat(receiver.stats().compactRebuilt()).isEqualTo(1);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
        assertThat(receiver.stats().fullBlockFallbacks()).isZero();

        // 恢复网络并等待：不会再有第二次补齐请求，迟到响应被丢弃，区块只应用一次。
        network.setDropRule(null);
        Thread.sleep(300);
        assertThat(stub.txRequests).hasSize(1);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
    }

    @Test
    void locallyFilledSlotIsNeverRequestedAgain(@TempDir Path dir) throws Exception {
        network = new LoopbackNetwork();
        List<Transaction> blockTxs = txs("tx-a", "tx-b", "tx-c");
        Block fullBlock = block("b1", "g", 0, blockTxs);
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        RelayConfig config = new RelayConfig(8, 5_000, 300, 100, 64, 16, 3);
        receiver = new RelayNode("receiver", config, network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();

        // 只丢弃补齐响应：请求仍到达对端并被记录，首批下标 0、2 进入在途，靠超时重试推进。
        network.setDropRule(message -> message instanceof TxResponseMessage);
        receiver.submitTransaction(blockTxs.get(1));
        network.send(com.github.guoyyit.compactblockrelayjava.support.TestFixtures
                .announce("stub", "receiver", fullBlock, 601L, 701L));
        waitFor(1000, () -> !stub.txRequests.isEmpty());
        List<Integer> firstIndexes = List.copyOf(stub.txRequests.get(0).indexes());
        assertThat(firstIndexes).containsExactlyInAnyOrder(0, 2);

        // 等待首个请求在途窗口的中段，再让 tx-a（下标 0）到达本地；下标 2 永远缺失。
        Thread.sleep(120);
        receiver.submitTransaction(blockTxs.get(0));

        // 等待若干次补齐超时后：首批之后的任何请求都不得再包含已被本地回填的下标 0；
        // 最终下标 2 始终缺失，回退完整区块。
        waitFor(2000, () -> receiver.stats().fullBlockFallbacks() >= 1);
        assertThat(stub.txRequests.size()).isGreaterThan(1);
        for (int i = 1; i < stub.txRequests.size(); i++) {
            com.github.guoyyit.compactblockrelayjava.protocol.TxRequestMessage request = stub.txRequests.get(i);
            assertThat(request.indexes()).doesNotContain(0);
        }
        network.setDropRule(null);
        waitFor(2000, () -> receiver.hasBlock("b1"));
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
    }

    @Test
    void lateTxResponseAfterLocalBackfillIsIgnoredAndBlockAppliedOnce(@TempDir Path dir)
            throws Exception {
        network = new LoopbackNetwork();
        List<Transaction> blockTxs = txs("tx-a", "tx-b");
        Block fullBlock = block("b1", "g", 0, blockTxs);
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        receiver = new RelayNode("receiver", slowTxTimeoutConfig(), network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();

        // 先丢弃补齐响应，让节点在途等待；本地新交易回填后立即完成上链。
        network.setDropRule(message -> message instanceof TxResponseMessage);
        receiver.submitTransaction(blockTxs.get(1));
        network.send(com.github.guoyyit.compactblockrelayjava.support.TestFixtures
                .announce("stub", "receiver", fullBlock, 1001L, 1101L));
        waitFor(1000, () -> !stub.txRequests.isEmpty());
        receiver.submitTransaction(blockTxs.get(0));
        waitFor(2000, () -> receiver.hasBlock("b1"));

        // 迟到的补齐响应恢复投递后必须被丢弃，不重复应用。
        network.setDropRule(null);
        Thread.sleep(300);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
        assertThat(receiver.stats().compactRebuilt()).isEqualTo(1);
    }

    @Test
    void ambiguousShortIdOnLateTransactionFallsBackToFullBlock(@TempDir Path dir)
            throws Exception {
        network = new LoopbackNetwork();
        Transaction realTx = TestFixtures.tx("real-late-tx");
        Transaction attackerTx = TestFixtures.tx("attacker-late-tx");
        Block fullBlock = block("b1", "g", 0, List.of(realTx));

        long k0 = 2_001L;
        long k1 = 2_011L;
        final long collisionShortId = SipHash.shortId(k0, k1, realTx.txid());

        // 交易池仅在真实交易进入后才对该短标识返回两个候选（模拟新交易到达时出现碰撞歧义）。
        Mempool collisionPool = new Mempool(100) {
            @Override
            public Set<Transaction> candidates(long key0, long key1, long shortId) {
                if (shortId == collisionShortId && super.get(realTx.txid()) != null) {
                    return Set.of(realTx, attackerTx);
                }
                return super.candidates(key0, key1, shortId);
            }
        };
        collisionPool.add(attackerTx);
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        receiver = new RelayNode("receiver", slowTxTimeoutConfig(), network,
                new FileBlockRepository(dir.resolve("receiver.blocks")),
                java.time.Clock.systemUTC(), collisionPool);
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();

        // 初始池中无候选，先发起缺失补齐；随后两笔碰撞交易进入本地，必须触发完整区块回退。
        network.setDropRule(message -> message instanceof TxResponseMessage);
        network.send(new AnnounceMessage("stub", "receiver", fullBlock.header(),
                List.of(collisionShortId), k0, k1));
        waitFor(1000, () -> !stub.txRequests.isEmpty());

        // 真实交易到达：此时该短标识出现两个候选，必须静默不填并回退完整区块。
        receiver.submitTransaction(realTx);

        waitFor(3000, () -> receiver.hasBlock("b1"));
        assertThat(receiver.stats().fullBlockFallbacks()).isGreaterThanOrEqualTo(1);
        assertThat(stub.blockRequests).isNotEmpty();
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
        network.setDropRule(null);
    }

    @Test
    void oneLocalTransactionFillsMultipleConcurrentSessionsIndependently(@TempDir Path dir)
            throws Exception {
        network = new LoopbackNetwork();
        // 两个同高度竞争区块各自缺失同一笔共享交易，通知并发到达形成两个在途会话。
        List<Transaction> blockOneTxs = txs("shared-tx", "b1-only");
        List<Transaction> blockTwoTxs = txs("shared-tx", "b2-only");
        Block blockOne = block("b1a", "g", 0, blockOneTxs);
        Block blockTwo = block("b1b", "g", 0, blockTwoTxs);
        StubPeer stub = new StubPeer("stub", network, blockTwo);
        RelayConfig config = slowTxTimeoutConfig();
        receiver = new RelayNode("receiver", config, network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();

        // 两个区块各自的独有交易已在本地，共享交易缺失，因此每个会话只请求一个下标。
        receiver.submitTransaction(blockOneTxs.get(1));
        receiver.submitTransaction(blockTwoTxs.get(1));

        network.setDropRule(message -> message instanceof TxResponseMessage);
        network.send(com.github.guoyyit.compactblockrelayjava.support.TestFixtures
                .announce("stub", "receiver", blockOne, 801L, 901L));
        network.send(com.github.guoyyit.compactblockrelayjava.support.TestFixtures
                .announce("stub", "receiver", blockTwo, 802L, 902L));
        waitFor(1000, () -> stub.txRequests.size() >= 2);

        // 共享交易在两个请求都在途期间到达：两个会话必须独立、互不影响地全部回填完成。
        receiver.submitTransaction(blockOneTxs.get(0));

        waitFor(2000, () -> receiver.stats().compactRebuilt() >= 2);
        assertThat(receiver.stats().compactRebuilt()).isEqualTo(2);
        assertThat(receiver.stats().fullBlockFallbacks()).isZero();
        assertThat(stub.txRequests).hasSize(2);
        // 同高度竞争区块只有一个能接上链尖，另一个进入孤儿区块队列，区块均未重复应用。
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
        Thread.sleep(300);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
    }
}
