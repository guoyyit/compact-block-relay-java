package com.github.guoyyit.compactblockrelayjava.support;

import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.network.Network;
import com.github.guoyyit.compactblockrelayjava.network.Peer;
import com.github.guoyyit.compactblockrelayjava.protocol.BlockRequestMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.BlockResponseMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.IndexedTx;
import com.github.guoyyit.compactblockrelayjava.protocol.Message;
import com.github.guoyyit.compactblockrelayjava.protocol.TxRequestMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.TxResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 测试用对等节点：记录收到的请求，并可决定补齐响应内容与完整区块响应内容。
 */
public class StubPeer implements Peer {
    private final String id;
    private final Network network;
    private final Block fullBlock;
    private final Predicate<Integer> maliciousIndex;

    public final List<TxRequestMessage> txRequests = new ArrayList<>();
    public final List<BlockRequestMessage> blockRequests = new ArrayList<>();

    public StubPeer(String id, Network network, Block fullBlock) {
        this(id, network, fullBlock, index -> false);
    }

    public StubPeer(String id, Network network, Block fullBlock, Predicate<Integer> maliciousIndex) {
        this.id = id;
        this.network = network;
        this.fullBlock = fullBlock;
        this.maliciousIndex = maliciousIndex;
    }

    @Override
    public void receive(Message message) {
        if (message instanceof TxRequestMessage request) {
            txRequests.add(request);
            List<IndexedTx> response = new ArrayList<>();
            for (int index : request.indexes()) {
                Transaction tx = fullBlock.transactions().get(index);
                if (maliciousIndex.test(index)) {
                    tx = TestFixtures.tx("malicious-content-for-" + index);
                }
                response.add(new IndexedTx(index, tx));
            }
            network.send(new TxResponseMessage(id, request.senderId(), request.blockHash(), response));
        } else if (message instanceof BlockRequestMessage request) {
            blockRequests.add(request);
            network.send(new BlockResponseMessage(id, request.senderId(), fullBlock));
        }
    }
}
