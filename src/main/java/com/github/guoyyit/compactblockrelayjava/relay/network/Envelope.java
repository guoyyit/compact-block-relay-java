package com.github.guoyyit.compactblockrelayjava.relay.network;

/**
 * 回环网络上传输的消息信封：来源节点、目标节点、消息体。
 */
public final class Envelope {

    private final String from;
    private final String to;
    private final Object payload;

    public Envelope(String from, String to, Object payload) {
        this.from = from;
        this.to = to;
        this.payload = payload;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public Object getPayload() {
        return payload;
    }
}
