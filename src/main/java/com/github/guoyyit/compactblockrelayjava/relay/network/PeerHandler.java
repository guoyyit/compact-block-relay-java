package com.github.guoyyit.compactblockrelayjava.relay.network;

/**
 * 节点消息处理器：回环网络将收到的信封派发给该处理器。
 */
public interface PeerHandler {

    void handle(Envelope envelope);
}
