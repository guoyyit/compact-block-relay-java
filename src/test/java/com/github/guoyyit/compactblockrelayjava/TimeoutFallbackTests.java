package com.github.guoyyit.compactblockrelayjava;

import com.github.guoyyit.compactblockrelayjava.crypto.SipHash;
import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.protocol.AnnounceMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.Message;
import com.github.guoyyit.compactblockrelayjava.protocol.TxRequestMessage;
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

class TimeoutFallbackTests {
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
    void missingTxRequestTimeoutRetriesThenFallsBackToFullBlock(@TempDir Path dir) throws Exception {
        network = new LoopbackNetwork();
        RelayConfig config = fastConfig();
        receiver = new RelayNode("receiver", config, network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        List<Transaction> blockTxs = txs("missing-tx");
        Block fullBlock = block("b1", "g", 0, blockTxs);
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();

        // 丢弃补齐请求与补齐响应路径：Stub 对请求的响应也会被丢弃，模拟请求持续失败。
        network.setDropRule(message -> message instanceof TxRequestMessage
                || (message instanceof com.github.guoyyit.compactblockrelayjava.protocol.TxResponseMessage));

        long k0 = 3L;
        long k1 = 4L;
        List<Long> shortIds = blockTxs.stream()
                .map(tx -> SipHash.shortId(k0, k1, tx.txid())).toList();
        network.send(new AnnounceMessage("stub", "receiver", fullBlock.header(), shortIds, k0, k1));

        waitFor(3000, () -> receiver.hasBlock("b1"));
        assertThat(stub.blockRequests).hasSize(1);
        assertThat(receiver.stats().fullBlockFallbacks()).isEqualTo(1);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
        assertThat(receiver.stats().compactRebuilt()).isZero();
        network.setDropRule(null);
    }

    @Test
    void rebuildTimeoutFallsBackWhenPeerNeverReplies(@TempDir Path dir) throws Exception {
        network = new LoopbackNetwork();
        RelayConfig config = new RelayConfig(8, 150, 100, 100, 64, 16, 0);
        receiver = new RelayNode("receiver", config, network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        List<Transaction> blockTxs = txs("missing-tx");
        Block fullBlock = block("b1", "g", 0, blockTxs);
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();

        network.setDropRule(message -> {
            boolean toReceiver = message.recipientId().equals("receiver");
            return toReceiver
                    && (message instanceof com.github.guoyyit.compactblockrelayjava.protocol.TxResponseMessage);
        });

        long k0 = 5L;
        long k1 = 6L;
        List<Long> shortIds = blockTxs.stream()
                .map(tx -> SipHash.shortId(k0, k1, tx.txid())).toList();
        network.send(new AnnounceMessage("stub", "receiver", fullBlock.header(), shortIds, k0, k1));

        waitFor(3000, () -> receiver.hasBlock("b1"));
        assertThat(stub.blockRequests).hasSize(1);
        assertThat(receiver.stats().fullBlockFallbacks()).isGreaterThanOrEqualTo(1);
        network.setDropRule(null);
    }

    @Test
    void lateTxResponseAfterFallbackIsIgnoredAndBlockAppliedOnce(@TempDir Path dir) throws Exception {
        network = new LoopbackNetwork();
        RelayConfig config = fastConfig();
        receiver = new RelayNode("receiver", config, network,
                new FileBlockRepository(dir.resolve("receiver.blocks")));
        List<Transaction> blockTxs = txs("missing-tx");
        Block fullBlock = block("b1", "g", 0, blockTxs);
        StubPeer stub = new StubPeer("stub", network, fullBlock);
        network.register("receiver", receiver);
        network.register("stub", stub);
        receiver.connectPeer("stub");
        receiver.start();

        network.setDropRule((Message message) ->
                message instanceof com.github.guoyyit.compactblockrelayjava.protocol.TxResponseMessage);

        long k0 = 8L;
        long k1 = 10L;
        List<Long> shortIds = blockTxs.stream()
                .map(tx -> SipHash.shortId(k0, k1, tx.txid())).toList();
        network.send(new AnnounceMessage("stub", "receiver", fullBlock.header(), shortIds, k0, k1));

        waitFor(3000, () -> receiver.hasBlock("b1"));
        network.setDropRule(null);
        Thread.sleep(300);
        assertThat(receiver.stats().blocksAccepted()).isEqualTo(1);
    }
}
