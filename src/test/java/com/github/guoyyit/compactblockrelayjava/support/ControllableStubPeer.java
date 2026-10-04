package com.github.guoyyit.compactblockrelayjava.support;

import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.network.Network;
import com.github.guoyyit.compactblockrelayjava.network.Peer;
import com.github.guoyyit.compactblockrelayjava.protocol.BlockRequestMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.IndexedTx;
import com.github.guoyyit.compactblockrelayjava.protocol.Message;
import com.github.guoyyit.compactblockrelayjava.protocol.TxRequestMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.TxResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 可控测试对端：始终记录收到的请求；是否回完整区块/补齐响应由开关决定，
 * 用于精确模拟“请求已到达但响应被丢弃/对端暂时不可用”。
 */
public class ControllableStubPeer implements Peer {
    private final String id;
    private final Network network;
    private final Block fullBlock;

    public final List<TxRequestMessage> txRequests = new ArrayList<>();
    public final List<BlockRequestMessage> blockRequests = new ArrayList<>();

    private Predicate<BlockRequestMessage> respondToBlockRequest = request -> true;
    // 默认不响应补齐请求：使用该 stub 的场景聚焦完整区块回退路径。
    private Predicate<TxRequestMessage> respondToTxRequest = request -> false;

    public ControllableStubPeer(String id, Network network, Block fullBlock) {
        this.id = id;
        this.network = network;
        this.fullBlock = fullBlock;
    }

    public void setRespondToBlockRequest(Predicate<BlockRequestMessage> rule) {
        this.respondToBlockRequest = rule;
    }

    public void setRespondToTxRequest(Predicate<TxRequestMessage> rule) {
        this.respondToTxRequest = rule;
    }

    @Override
    public void receive(Message message) {
        if (message instanceof TxRequestMessage request) {
            txRequests.add(request);
            if (!respondToTxRequest.test(request)) {
                return;
            }
            List<IndexedTx> response = new ArrayList<>();
            for (int index : request.indexes()) {
                response.add(new IndexedTx(index, fullBlock.transactions().get(index)));
            }
            network.send(new TxResponseMessage(id, request.senderId(), request.blockHash(), response));
        } else if (message instanceof BlockRequestMessage request) {
            blockRequests.add(request);
            if (respondToBlockRequest.test(request)) {
                network.send(new com.github.guoyyit.compactblockrelayjava.protocol.BlockResponseMessage(
                        id, request.senderId(), fullBlock));
            }
        }
    }
}
