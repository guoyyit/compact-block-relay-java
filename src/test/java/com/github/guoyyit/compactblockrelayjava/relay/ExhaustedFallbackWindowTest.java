package com.github.guoyyit.compactblockrelayjava.relay;

import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.network.Network;
import com.github.guoyyit.compactblockrelayjava.protocol.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.Deque;
import java.util.Set;

import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.block;
import static com.github.guoyyit.compactblockrelayjava.support.TestFixtures.txs;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回退耗尽待重新接纳哈希窗口的有界性单元验证。
 */
class ExhaustedFallbackWindowTest {

    private static final Network NOOP_NETWORK = new Network() {
        @Override
        public void register(String id, com.github.guoyyit.compactblockrelayjava.network.Peer peer) {
        }

        @Override
        public void unregister(String id) {
        }

        @Override
        public void send(Message message) {
        }

        @Override
        public void shutdown() {
        }
    };

    @Test
    @SuppressWarnings("unchecked")
    void exhaustedWindowIsBoundedByMaxSeenHashes(@TempDir Path dir)
            throws Exception {
        RelayConfig config = new RelayConfig(8, 5_000, 5_000, 100, 3, 16, 0, 5_000, 2);
        RelayNode node = new RelayNode("n", config, NOOP_NETWORK,
                new com.github.guoyyit.compactblockrelayjava.storage.FileBlockRepository(
                        dir.resolve("n.blocks")));
        try {
            node.start();
            Field dequeField = RelayNode.class.getDeclaredField("exhaustedFallbacks");
            Field setField = RelayNode.class.getDeclaredField("exhaustedSet");
            dequeField.setAccessible(true);
            setField.setAccessible(true);
            Deque<String> deque = (Deque<String>) dequeField.get(node);
            Set<String> set = (Set<String>) setField.get(node);

            for (int i = 0; i < 10; i++) {
                Block block = block("b" + i, "g", 0, txs("tx" + i));
                node.submitTransaction(block.transactions().get(0));
            }
            // 直接通过反射调用内部记录方法模拟 10 个不同区块回退耗尽。
            java.lang.reflect.Method remember =
                    RelayNode.class.getDeclaredMethod("rememberExhausted", String.class);
            remember.setAccessible(true);
            for (int i = 0; i < 10; i++) {
                remember.invoke(node, "b" + i);
            }
            assertThat(deque).hasSize(3);
            assertThat(set).hasSize(3);
            assertThat(deque).containsExactly("b7", "b8", "b9");
        } finally {
            node.shutdown();
        }
    }
}
