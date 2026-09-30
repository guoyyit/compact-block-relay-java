package com.github.guoyyit.compactblockrelayjava.network;

import com.github.guoyyit.compactblockrelayjava.protocol.Message;

/**
 * 本地回环网络抽象。
 */
public interface Network {
    void register(String id, Peer peer);

    void unregister(String id);

    void send(Message message);

    void shutdown();
}
