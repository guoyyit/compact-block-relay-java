package com.github.guoyyit.compactblockrelayjava.relay;

/**
 * 单个区块的完整区块回退状态。仅由所属节点事件循环访问，无需加锁。
 */
public class FallbackSession {
    private final String blockHash;
    private final String peerId;
    private final int maxRetries;
    private int attempts;

    public FallbackSession(String blockHash, String peerId, int maxRetries) {
        this.blockHash = blockHash;
        this.peerId = peerId;
        this.maxRetries = maxRetries;
    }

    public String blockHash() {
        return blockHash;
    }

    public String peerId() {
        return peerId;
    }

    public int attempts() {
        return attempts;
    }

    /**
     * 记录一次回退请求（含首次请求），返回发送后的请求序号（从 1 开始）。
     */
    public int recordAttempt() {
        attempts++;
        return attempts;
    }

    /**
     * 已用完全部有限重试（请求次数达到 1 + maxRetries 后仍无响应）。
     */
    public boolean retriesExhausted() {
        return attempts > maxRetries;
    }
}
