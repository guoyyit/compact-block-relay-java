package com.github.guoyyit.compactblockrelayjava;

import com.github.guoyyit.compactblockrelayjava.crypto.SipHash;
import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.model.BlockHeader;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.pool.Mempool;
import com.github.guoyyit.compactblockrelayjava.protocol.AnnounceMessage;
import com.github.guoyyit.compactblockrelayjava.relay.RelayConfig;
import com.github.guoyyit.compactblockrelayjava.relay.RelayNode;
import com.github.guoyyit.compactblockrelayjava.storage.FileBlockRepository;
import com.github.guoyyit.compactblockrelayjava.support.StubPeer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.block;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.tx;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.txs;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

class ShortIdCollisionTests {
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

    @Test
    void ambiguousShortIdTriggersFullBlockFallback(@TempDir Path dir) throws Exception {
        network = new LoopbackNetwork();
        Transaction realTx = tx("real-tx");
        Transaction attackerTx = tx("attacker-tx");
        List<Transaction> blockTxs = List.of(realTx);
        Block fullBlock = block("b1", "g", 0, blockTxs);

        long k0 = 123456789L;
        long k1 = 987654321L;
        long collisionShortId = SipHash.shortId(k0, k1, realTx.txid());

        // 测试用交易池：对 collisionShortId 同时返回真实交易与另一笔候选（模拟自然短标识碰撞）。
        Mempool collisionPool = new Mempool(100) {
            @Override
            public Set<Transaction> candidates(long key0, long key1, long shortId) {
                if (shortId == collisionShortId) {
                    return Set.of(realTx, attackerTx);
                }
                return super.candidates(key0, key1, shortId);
            }
        };
        collisionPool.add(realTx);
        collisionPool.add(attackerTx);
        receiver = new RelayNode("receiver", RelayConfig.defaults(), network,
                new FileBlockRepository(dir.resolve("receiver.blocks")),
                java.time.Clock.systemUTC(), collisionPool);
        StubPeer stub = new StubPeer("stub", network, fullBlock);

        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();

        BlockHeader header = fullBlock.header();
        network.send(new AnnounceMessage("stub", "receiver", header,
                List.of(collisionShortId), k0, k1));

        waitFor(3000, () -> receiver.hasBlock("b1"));
        assertThat(receiver.stats().fullBlockFallbacks()).isEqualTo(1);
        assertThat(stub.blockRequests).hasSize(1);
        assertThat(stub.txRequests).isEmpty();
        assertThat(receiver.stats().compactRebuilt()).isZero();
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
    }

    @Test
    void wrongTransactionContentInTxResponseTriggersFallback(@TempDir Path dir) throws Exception {
        network = new LoopbackNetwork();
        List<Transaction> blockTxs = txs("missing-tx", "present-tx");
        Block fullBlock = block("b1", "g", 0, blockTxs);
        StubPeer stub = new StubPeer("stub", network, fullBlock, index -> index == 0);
        receiver = new RelayNode("receiver", RelayConfig.defaults(), network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));

        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();
        receiver.submitTransaction(blockTxs.get(1));

        long k0 = 7L;
        long k1 = 9L;
        List<Long> shortIds = blockTxs.stream()
                .map(transaction -> SipHash.shortId(k0, k1, transaction.txid()))
                .toList();
        network.send(new AnnounceMessage("stub", "receiver", fullBlock.header(), shortIds, k0, k1));

        waitFor(3000, () -> receiver.hasBlock("b1"));
        assertThat(receiver.stats().fullBlockFallbacks()).isEqualTo(1);
        assertThat(stub.blockRequests).hasSize(1);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
    }
}
