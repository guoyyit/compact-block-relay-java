package com.github.guoyyit.compactblockrelayjava;

import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.protocol.BlockRequestMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.BlockResponseMessage;
import com.github.guoyyit.compactblockrelayjava.relay.RelayConfig;
import com.github.guoyyit.compactblockrelayjava.relay.RelayNode;
import com.github.guoyyit.compactblockrelayjava.storage.FileBlockRepository;
import com.github.guoyyit.compactblockrelayjava.support.ControllableStubPeer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.announce;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.block;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.txs;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 完整区块回退自身的超时、有限重试、耗尽后重新接纳与迟到消息丢弃。
 */
class FallbackReliabilityTests {
    private LoopbackNetwork network;
    private RelayNode receiver;
    private ControllableStubPeer stub;
    private Block fullBlock;

    @AfterEach
    void tearDown() {
        if (receiver != null) {
            receiver.shutdown();
        }
        if (network != null) {
            network.shutdown();
        }
    }

    private RelayConfig fastFallbackConfig() {
        // 回退请求超时 200ms，最多再重试 2 次（共 3 次请求）。
        return new RelayConfig(8, 300, 5_000, 100, 64, 16, 0, 200, 2);
    }

    private void startReceiver(RelayConfig config, Path dir, List<Transaction> blockTxs) {
        network = new LoopbackNetwork();
        fullBlock = block("b1", "g", 0, blockTxs);
        stub = new ControllableStubPeer("stub", network, fullBlock);
        receiver = new RelayNode("receiver", config, network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();
    }

    private void announceMissingBlock(long k0, long k1) {
        network.send(announce("stub", "receiver", fullBlock, k0, k1));
    }

    @Test
    void droppedFallbackRequestsRetryThenSucceed(@TempDir Path dir) throws Exception {
        startReceiver(fastFallbackConfig(), dir, txs("missing-tx"));

        // 对端收到前两次完整区块请求但不响应（请求/响应在传输中丢失），第三次才响应。
        stub.setRespondToBlockRequest(request -> stub.blockRequests.size() >= 3);
        announceMissingBlock(11L, 22L);

        waitFor(3000, () -> receiver.hasBlock("b1"));
        assertThat(stub.blockRequests).hasSize(3);
        assertThat(receiver.stats().blockRequests()).isEqualTo(3);
        assertThat(receiver.stats().fallbackRetries()).isEqualTo(2);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
    }

    @Test
    void exhaustedFallbackReleasesPlaceholderAndReAnnouncementRecovers(@TempDir Path dir)
            throws Exception {
        startReceiver(fastFallbackConfig(), dir, txs("missing-tx"));

        // 对端暂时不可用：收到完整区块请求但一律不响应，回退重试最终耗尽。
        stub.setRespondToBlockRequest(request -> false);
        announceMissingBlock(31L, 41L);

        // 首次 + 2 次重试全部超时，回退耗尽并释放占位。
        waitFor(3000, () -> receiver.stats().fallbackExhausted() >= 1);
        assertThat(stub.blockRequests).hasSize(3);
        assertThat(receiver.hasBlock("b1")).isFalse();

        // 耗尽后重复通知到达：作为“重新接纳”重新进入流程（请求仍被忽略，会再次耗尽），
        // 不得被当作普通重复通知丢弃。
        announceMissingBlock(31L, 41L);
        waitFor(1000, () -> receiver.stats().reacceptedAnnouncements() >= 1);
        assertThat(receiver.stats().reacceptedAnnouncements()).isEqualTo(1);

        // 对端仍不可用，等待第二次回退也耗尽。
        waitFor(2000, () -> receiver.stats().fallbackExhausted() >= 2);
        assertThat(receiver.hasBlock("b1")).isFalse();

        // 网络恢复，对端再次重发通知：必须最终上链，区块只应用一次。
        stub.setRespondToBlockRequest(request -> true);
        int before = receiver.stats().duplicateAnnouncementsDropped();
        announceMissingBlock(31L, 41L);
        waitFor(3000, () -> receiver.hasBlock("b1"));
        assertThat(receiver.stats().reacceptedAnnouncements()).isEqualTo(2);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);

        // 上链后的重复通知仍按已接受去重。
        announceMissingBlock(31L, 41L);
        Thread.sleep(300);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
        assertThat(receiver.stats().duplicateAnnouncementsDropped()).isGreaterThan(before);
    }

    @Test
    void repeatedAnnouncementsDuringFallbackCauseSingleRequestChain(@TempDir Path dir)
            throws Exception {
        startReceiver(fastFallbackConfig(), dir, txs("missing-tx"));

        // 对端不可用，期间多次重复通知同一区块。
        stub.setRespondToBlockRequest(request -> false);
        announceMissingBlock(51L, 61L);
        waitFor(1000, () -> !stub.blockRequests.isEmpty());
        for (int i = 0; i < 5; i++) {
            announceMissingBlock(51L, 61L);
            Thread.sleep(100);
        }

        // 重复通知不产生额外回退请求：请求总数不超过首次 + 重试次数，且只针对同一区块。
        Thread.sleep(600);
        assertThat(stub.blockRequests).hasSizeLessThanOrEqualTo(3);
        assertThat(stub.blockRequests.stream().map(BlockRequestMessage::blockHash).distinct().count())
                .isEqualTo(1);

        // 恢复网络，等待回退耗尽后重新通知，完成上链且区块只应用一次。
        stub.setRespondToBlockRequest(request -> true);
        waitFor(2000, () -> receiver.stats().fallbackExhausted() >= 1);
        announceMissingBlock(51L, 61L);
        waitFor(3000, () -> receiver.hasBlock("b1"));
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
    }

    @Test
    void exhaustedFallbackRecordsRemainBounded(@TempDir Path dir) throws Exception {
        // 对同一区块反复“回退耗尽 -> 重新通知”，待重新接纳记录不得随循环次数无界增长：
        // 节点在多轮后仍稳定，重新接纳计数随通知次数增长（说明记录被正确复用/清理）。
        RelayConfig config = new RelayConfig(8, 5_000, 100, 100, 4_096, 16, 0, 80, 0);
        startReceiver(config, dir, txs("missing-tx"));
        stub.setRespondToBlockRequest(request -> false);
        announceMissingBlock(91L, 92L);
        int rounds = 4;
        for (int round = 1; round <= rounds; round++) {
            int expected = round;
            waitFor(3000, () -> receiver.stats().fallbackExhausted() >= expected);
            if (round < rounds) {
                announceMissingBlock(91L, 92L);
                waitFor(1000, () -> receiver.stats().reacceptedAnnouncements() >= expected);
            }
        }
        assertThat(receiver.stats().fallbackExhausted()).isEqualTo(rounds);
        assertThat(receiver.stats().reacceptedAnnouncements()).isEqualTo(rounds - 1);
        assertThat(receiver.hasBlock("b1")).isFalse();
    }

    @Test
    void lateBlockResponseAfterCompletionIsIgnored(@TempDir Path dir) throws Exception {
        startReceiver(new RelayConfig(8, 5_000, 5_000, 100, 64, 16, 0, 5_000, 2),
                dir, txs("tx-a"));

        // 本地已持有全部交易：直接紧凑重建上链，不发生回退。
        receiver.submitTransaction(fullBlock.transactions().get(0));
        announceMissingBlock(71L, 81L);
        waitFor(2000, () -> receiver.hasBlock("b1"));

        // 之后到达的迟到完整区块响应必须被安全丢弃，区块不被重复应用。
        network.send(new BlockResponseMessage("stub", "receiver", fullBlock));
        Thread.sleep(300);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
        assertThat(receiver.stats().compactRebuilt()).isEqualTo(1);
        assertThat(stub.blockRequests).isEmpty();
    }
}
