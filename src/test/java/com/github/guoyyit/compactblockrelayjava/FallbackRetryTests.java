package com.github.guoyyit.compactblockrelayjava;

import com.github.guoyyit.compactblockrelayjava.crypto.SipHash;
import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.protocol.AnnounceMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.BlockRequestMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.BlockResponseMessage;
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

import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.block;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.txs;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

class FallbackRetryTests {
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

    /** 补齐与回退超时均为 80ms；回退仅重试 1 次（共 2 次请求），重建超时 30s 避免干扰。 */
    private RelayConfig config() {
        return new RelayConfig(8, 30_000, 80, 100, 64, 16, 0, 80, 1);
    }

    private AnnounceMessage announceFor(Block fullBlock, long k0, long k1) {
        List<Transaction> txs = fullBlock.transactions();
        List<Long> shortIds = txs.stream()
                .map(tx -> SipHash.shortId(k0, k1, tx.txid())).toList();
        return new AnnounceMessage("stub", "receiver", fullBlock.header(), shortIds, k0, k1);
    }

    @Test
    void droppedFallbackRequestsAreRetriedThenReannounceAfterExhaustionRecovers(
            @TempDir Path dir) throws Exception {
        network = new LoopbackNetwork();
        receiver = new RelayNode("receiver", config(), network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        Block fullBlock = block("b1", "g", 0, txs("missing-tx"));
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();

        // 补齐请求/响应与完整区块请求全部丢弃：回退请求与重试都无法送达。
        network.setDropRule(message -> message instanceof com.github.guoyyit
                .compactblockrelayjava.protocol.TxRequestMessage
                || message instanceof com.github.guoyyit.compactblockrelayjava
                .protocol.TxResponseMessage
                || message instanceof BlockRequestMessage);

        network.send(announceFor(fullBlock, 71L, 72L));

        // 首次回退请求 + 1 次重试，共 2 次（请求在网络中被丢弃，以接收方计数为准）。
        waitFor(2000, () -> receiver.stats().blockRequests() >= 2);
        assertThat(receiver.stats().blockRequests()).isEqualTo(2);
        assertThat(receiver.stats().fallbackRetries()).isEqualTo(1);
        assertThat(receiver.hasBlock("b1")).isFalse();

        // 等待重试耗尽（2 次请求后再等一个超时周期），进行中占位被释放。
        Thread.sleep(250);
        network.setDropRule(message -> message instanceof BlockRequestMessage);

        // 占位释放后重新通知；为吸收耗尽时刻的时序边界，在短窗口内幂等重发若干次。
        for (int i = 0; i < 10 && receiver.stats().fallbackReadmissions() == 0; i++) {
            network.send(announceFor(fullBlock, 81L + i, 82L + i));
            Thread.sleep(50);
        }
        assertThat(receiver.stats().fallbackReadmissions()).isEqualTo(1);
        waitFor(2000, () -> receiver.hasBlock("b1"));
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
        // 重新接纳后既可经紧凑补齐也可经完整区块回退完成，二者必有其一在恢复后产生请求。
        boolean recoveredViaFallback = receiver.stats().blockRequests() >= 3;
        boolean recoveredViaCompact = stub.txRequests.stream()
                .anyMatch(request -> request.blockHash().equals("b1"))
                && receiver.stats().compactRebuilt() >= 1;
        assertThat(recoveredViaFallback || recoveredViaCompact).isTrue();

        // 迟到的完整区块响应与再一次重复通知都不能重复上链。
        network.setDropRule(null);
        network.send(new BlockResponseMessage("stub", "receiver", fullBlock));
        network.send(announceFor(fullBlock, 91L, 92L));
        Thread.sleep(300);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
        network.setDropRule(null);
    }

    @Test
    void reannounceAfterExhaustionRecoversViaFallbackAndAppliesOnce(@TempDir Path dir)
            throws Exception {
        network = new LoopbackNetwork();
        receiver = new RelayNode("receiver", config(), network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        Block fullBlock = block("b2", "g", 0, txs("missing-tx-2"));
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();

        // 回退请求持续丢弃；补齐响应也持续丢弃，使重新接纳后只能再次走回退路径。
        network.setDropRule(message -> message instanceof BlockRequestMessage
                || message instanceof com.github.guoyyit.compactblockrelayjava
                .protocol.TxResponseMessage);
        network.send(announceFor(fullBlock, 101L, 102L));
        waitFor(2000, () -> receiver.stats().blockRequests() >= 2);
        Thread.sleep(250);

        // 对端恢复（回退请求可送达，完整区块响应将由 stub 正常返回）。
        network.setDropRule(message -> message instanceof com.github.guoyyit.compactblockrelayjava
                .protocol.TxResponseMessage);
        for (int i = 0; i < 10 && receiver.stats().fallbackReadmissions() == 0; i++) {
            network.send(announceFor(fullBlock, 111L + i, 112L + i));
            Thread.sleep(50);
        }

        waitFor(2000, () -> receiver.hasBlock("b2"));
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
        assertThat(receiver.stats().blockRequests()).isGreaterThanOrEqualTo(3);
        assertThat(receiver.stats().fallbackReadmissions()).isEqualTo(1);

        // 迟到完整区块响应与重复通知安全丢弃，区块只应用一次。
        network.setDropRule(null);
        network.send(new BlockResponseMessage("stub", "receiver", fullBlock));
        network.send(announceFor(fullBlock, 121L, 122L));
        Thread.sleep(300);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
    }

    @Test
    void duplicateAnnouncementsDuringPendingFallbackDoNotCreateExtraRequests(
            @TempDir Path dir) throws Exception {
        network = new LoopbackNetwork();
        receiver = new RelayNode("receiver", config(), network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        Block fullBlock = block("b3", "g", 0, txs("missing-tx-3"));
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.connectPeer("other");
        receiver.start();

        // 完整区块请求与响应均丢弃，使回退长时间停留在进行中（重试中）。
        network.setDropRule(message -> message instanceof BlockRequestMessage
                || message instanceof BlockResponseMessage
                || message instanceof com.github.guoyyit.compactblockrelayjava
                .protocol.TxResponseMessage);
        network.send(announceFor(fullBlock, 131L, 132L));
        // 等待全部有限重试发出（首次 + 1 次重试），再比较重复通知前后的请求数。
        waitFor(2000, () -> receiver.stats().blockRequests() >= 2);
        int requestsAtPending = receiver.stats().blockRequests();
        assertThat(requestsAtPending).isEqualTo(2);

        // 最后一次请求的超时窗口内，原对端与其他对端重复通知：不得引发额外请求。
        AnnounceMessage repeat = announceFor(fullBlock, 141L, 142L);
        network.send(repeat);
        network.send(new AnnounceMessage("other", "receiver", fullBlock.header(),
                repeat.shortIds(), repeat.k0(), repeat.k1()));
        Thread.sleep(50);
        assertThat(receiver.stats().blockRequests()).isEqualTo(requestsAtPending);
        assertThat(receiver.stats().fallbackReadmissions()).isZero();
        assertThat(receiver.hasBlock("b3")).isFalse();
        network.setDropRule(null);
    }
}
