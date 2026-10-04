package com.github.guoyyit.compactblockrelayjava;

import com.github.guoyyit.compactblockrelayjava.crypto.SipHash;
import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.pool.Mempool;
import com.github.guoyyit.compactblockrelayjava.protocol.AnnounceMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.Message;
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
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.txs;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

class RealtimeTxFillTests {
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

    private RelayConfig fastConfig() {
        return new RelayConfig(8, 600, 120, 100, 64, 16, 1);
    }

    @Test
    void missingTxArrivingDuringRebuildCompletesImmediatelyWithoutWaitingForResponse(
            @TempDir Path dir) throws Exception {
        network = new LoopbackNetwork();
        receiver = new RelayNode("receiver", fastConfig(), network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        List<Transaction> blockTxs = txs("present-tx", "late-tx");
        Block fullBlock = block("b1", "g", 0, blockTxs);
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();
        receiver.submitTransaction(blockTxs.get(0));

        // 持续丢弃补齐响应：若不利用本地新交易回填，重建只能靠超时回退（>600ms）。
        network.setDropRule((Message message) ->
                message instanceof com.github.guoyyit.compactblockrelayjava.protocol.TxResponseMessage);

        long k0 = 21L;
        long k1 = 22L;
        List<Long> shortIds = blockTxs.stream()
                .map(tx -> SipHash.shortId(k0, k1, tx.txid())).toList();
        network.send(new AnnounceMessage("stub", "receiver", fullBlock.header(), shortIds, k0, k1));

        // 补齐请求确已发出（槽位缺失）。
        waitFor(1000, () -> !stub.txRequests.isEmpty());
        assertThat(stub.txRequests).hasSize(1);
        assertThat(stub.txRequests.get(0).indexes()).containsExactly(1);

        // 本地新收到缺失槽位对应的交易：立即完成并上链，不等待在途补齐响应。
        receiver.submitTransaction(blockTxs.get(1));
        waitFor(1000, () -> receiver.hasBlock("b1"));
        assertThat(receiver.stats().compactRebuilt()).isEqualTo(1);
        assertThat(receiver.stats().fullBlockFallbacks()).isZero();
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
        network.setDropRule(null);
    }

    @Test
    void filledSlotIsNotRequestedAgainAndLateResponseIsDiscarded(@TempDir Path dir)
            throws Exception {
        network = new LoopbackNetwork();
        RelayConfig config = new RelayConfig(8, 5_000, 120, 100, 64, 16, 3, 1_000, 2);
        receiver = new RelayNode("receiver", config, network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        List<Transaction> blockTxs = txs("late-a", "late-b");
        Block fullBlock = block("b1", "g", 0, blockTxs);
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();

        // 首批补齐响应被丢弃：触发超时重试，此时槽位仍全部缺失，重试携带两个下标。
        network.setDropRule((Message message) ->
                message instanceof com.github.guoyyit.compactblockrelayjava.protocol.TxResponseMessage
                        && message.recipientId().equals("receiver"));

        long k0 = 31L;
        long k1 = 32L;
        List<Long> shortIds = blockTxs.stream()
                .map(tx -> SipHash.shortId(k0, k1, tx.txid())).toList();
        network.send(new AnnounceMessage("stub", "receiver", fullBlock.header(), shortIds, k0, k1));

        waitFor(1000, () -> receiver.stats().txRequests() >= 2);
        // 两个槽位各被请求两次（首次 + 一次重试），尚无槽位通过本地新交易填入。
        assertThat(stub.txRequests).hasSize(2);
        for (var request : stub.txRequests) {
            assertThat(request.indexes()).containsExactly(0, 1);
        }

        // 先本地补入槽位 0，再恢复响应投递：迟到响应只能填槽位 1，槽位 0 不再被请求。
        receiver.submitTransaction(blockTxs.get(0));
        network.setDropRule(null);

        waitFor(1000, () -> receiver.hasBlock("b1"));
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
        assertThat(receiver.stats().compactRebuilt()).isEqualTo(1);
        assertThat(receiver.stats().fullBlockFallbacks()).isZero();
        // 此后即使补齐链路再产生任何响应，区块也只应用一次。
        Thread.sleep(300);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
    }

    @Test
    void timeoutRetryNeverIncludesSlotFilledLocallyWhileRequestInFlight(@TempDir Path dir)
            throws Exception {
        network = new LoopbackNetwork();
        RelayConfig config = new RelayConfig(8, 5_000, 80, 100, 64, 16, 3, 1_000, 2);
        receiver = new RelayNode("receiver", config, network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        List<Transaction> blockTxs = txs("fill-a", "fill-b");
        Block fullBlock = block("b1", "g", 0, blockTxs);
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();

        // 补齐响应始终丢弃，只能靠超时重试推进。
        network.setDropRule((Message message) ->
                message instanceof com.github.guoyyit.compactblockrelayjava.protocol.TxResponseMessage);

        long k0 = 71L;
        long k1 = 72L;
        List<Long> shortIds = blockTxs.stream()
                .map(tx -> SipHash.shortId(k0, k1, tx.txid())).toList();
        network.send(new AnnounceMessage("stub", "receiver", fullBlock.header(), shortIds, k0, k1));

        waitFor(1000, () -> !stub.txRequests.isEmpty());
        assertThat(stub.txRequests.get(0).indexes()).containsExactly(0, 1);

        // 首批请求仍在途时本地补入槽位 0：后续重试只能包含槽位 1，槽位 0 至多被请求一次。
        receiver.submitTransaction(blockTxs.get(0));
        waitFor(1000, () -> stub.txRequests.size() >= 3);
        for (var request : stub.txRequests.subList(1, stub.txRequests.size())) {
            assertThat(request.indexes()).containsExactly(1);
        }
        long requestsForSlot0 = stub.txRequests.stream()
                .filter(request -> request.indexes().contains(0)).count();
        assertThat(requestsForSlot0).isEqualTo(1);

        // 本地再补入槽位 1：立即完成，无需再等补齐响应，也不再发任何请求。
        int requestsSoFar = stub.txRequests.size();
        receiver.submitTransaction(blockTxs.get(1));
        waitFor(1000, () -> receiver.hasBlock("b1"));
        Thread.sleep(200);
        assertThat(stub.txRequests).hasSize(requestsSoFar);
        assertThat(receiver.stats().compactRebuilt()).isEqualTo(1);
        network.setDropRule(null);
    }

    @Test
    void oneNewTransactionFillsIndependentSlotsAcrossTwoInFlightSessions(@TempDir Path dir)
            throws Exception {
        network = new LoopbackNetwork();
        RelayConfig config = new RelayConfig(8, 5_000, 120, 100, 64, 16, 3, 1_000, 2);
        receiver = new RelayNode("receiver", config, network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        // 同一条链上高度相邻的两个区块，共享一笔双方都缺失的交易，另一笔交易各不相同。
        Transaction shared = com.github.guoyyit.compactblockrelayjava.support.TestFixtures
                .tx("shared-late-tx");
        Transaction onlyA = com.github.guoyyit.compactblockrelayjava.support.TestFixtures.tx("only-a");
        Transaction onlyB = com.github.guoyyit.compactblockrelayjava.support.TestFixtures.tx("only-b");
        Block blockA = block("b-a", "g", 0, List.of(shared, onlyA));
        Block blockB = block("b-b", "b-a", 1, List.of(shared, onlyB));
        StubPeer stubA = new StubPeer("stub-a", network, blockA);
        StubPeer stubB = new StubPeer("stub-b", network, blockB);
        network.register("receiver", receiver);
        network.register("stub-a", stubA);
        network.register("stub-b", stubB);
        receiver.connectPeer("stub-a");
        receiver.connectPeer("stub-b");
        receiver.start();

        // 两个区块的补齐响应均丢弃：只能依赖本地新交易回填才能提前完成。
        network.setDropRule((Message message) ->
                message instanceof com.github.guoyyit.compactblockrelayjava.protocol.TxResponseMessage);

        long k0 = 41L;
        long k1 = 42L;
        network.send(new AnnounceMessage("stub-a", "receiver", blockA.header(),
                shortIdsOf(blockA, k0, k1), k0, k1));
        long otherK0 = 51L;
        long otherK1 = 52L;
        network.send(new AnnounceMessage("stub-b", "receiver", blockB.header(),
                shortIdsOf(blockB, otherK0, otherK1), otherK0, otherK1));

        waitFor(1000, () -> stubA.txRequests.size() + stubB.txRequests.size() >= 2);

        // 两笔各自独有交易与共享交易先后本地到达：共享交易必须同时填入两个会话。
        receiver.submitTransaction(onlyA);
        receiver.submitTransaction(onlyB);
        receiver.submitTransaction(shared);

        waitFor(1000, () -> receiver.hasBlock("b-a"));
        waitFor(1000, () -> receiver.hasBlock("b-b"));
        // 两个会话各自只接受一次，且均为紧凑重建（未走完整区块回退）。
        assertThat(receiver.stats().compactRebuilt()).isEqualTo(2);
        assertThat(receiver.stats().fullBlockFallbacks()).isZero();
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(2);
        network.setDropRule(null);
    }

    private static List<Long> shortIdsOf(Block block, long k0, long k1) {
        return block.header().txids().stream()
                .map(txid -> SipHash.shortId(k0, k1, txid))
                .toList();
    }

    @Test
    void ambiguousShortIdOnIncomingTxIsNotSilentlyFilledAndTriggersFallback(@TempDir Path dir)
            throws Exception {
        network = new LoopbackNetwork();
        Transaction lateTx = com.github.guoyyit.compactblockrelayjava.support.TestFixtures
                .tx("arriving-real-tx");
        Transaction colliding = com.github.guoyyit.compactblockrelayjava.support.TestFixtures
                .tx("arriving-colliding-tx");
        Block fullBlock = block("b1", "g", 0, List.of(lateTx));
        long k0 = 61L;
        long k1 = 62L;
        long collisionShortId = SipHash.shortId(k0, k1, lateTx.txid());

        Mempool collisionPool = new Mempool(100) {
            @Override
            public Set<Transaction> candidates(long key0, long key1, long shortId) {
                if (shortId == collisionShortId) {
                    return get(lateTx.txid()) == null ? Set.of() : Set.of(lateTx, colliding);
                }
                return super.candidates(key0, key1, shortId);
            }
        };
        receiver = new RelayNode("receiver", fastConfig(), network,
                new FileBlockRepository(dir.resolve("receiver.blocks")),
                java.time.Clock.systemUTC(), collisionPool);
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();

        // 补齐响应丢弃：确保回退只能由回填时发现的短标识歧义触发。
        network.setDropRule((Message message) ->
                message instanceof com.github.guoyyit.compactblockrelayjava.protocol.TxResponseMessage);
        network.send(new AnnounceMessage("stub", "receiver", fullBlock.header(),
                List.of(collisionShortId), k0, k1));
        waitFor(1000, () -> !stub.txRequests.isEmpty());

        // 缺失交易本地到达，但该短标识此刻出现两个候选：不得静默填入，必须回退完整区块。
        receiver.submitTransaction(lateTx);
        waitFor(1000, () -> receiver.hasBlock("b1"));
        assertThat(receiver.stats().fullBlockFallbacks()).isEqualTo(1);
        waitFor(1000, () -> stub.blockRequests.size() == 1);
        assertThat(stub.blockRequests).hasSize(1);
        assertThat(receiver.stats().compactRebuilt()).isZero();
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
        network.setDropRule(null);
    }
}
