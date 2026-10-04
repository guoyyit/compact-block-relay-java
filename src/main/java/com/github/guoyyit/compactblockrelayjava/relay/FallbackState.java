package com.github.guoyyit.compactblockrelayjava.relay;

/**
 * 单个区块的完整区块回退状态：记录请求对端、已发出的请求次数（含首次）
 * 与当前在途请求的代号。仅由所属节点事件循环访问。
 */
public class FallbackState {
    private final String peerId;
    private int requestsSent;
    private long inFlightToken;

    public FallbackState(String peerId) {
        this.peerId = peerId;
    }

    public String peerId() {
        return peerId;
    }

    public int requestsSent() {
        return requestsSent;
    }

    public long inFlightToken() {
        return inFlightToken;
    }

    /**
     * 登记一次新发出的请求，返回该请求的唯一代号（单调递增）。
     */
    public long requestSent() {
        requestsSent++;
        inFlightToken++;
        return inFlightToken;
    }
}
