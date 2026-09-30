package com.github.guoyyit.compactblockrelayjava.network;

import com.github.guoyyit.compactblockrelayjava.protocol.Message;

/**
 * 可接收消息的节点。
 */
public interface Peer {
    void receive(Message message);
}
