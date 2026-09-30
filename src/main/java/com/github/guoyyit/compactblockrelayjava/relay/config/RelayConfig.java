package com.github.guoyyit.compactblockrelayjava.relay.config;

/**
 * 紧凑区块传播协议配置参数。
 */
public final class RelayConfig {

    private int shortTxIdBytes = 6;
    private long rebuildTimeoutMillis = 2000L;
    private long requestTimeoutMillis = 1000L;
    private int fullBlockRetry = 1;
    private int mempoolMaxSize = 10_000;
    private int seenAnnouncementMaxSize = 10_000;
    private int orphanMaxSize = 1_000;
    private int knownBlockMaxSize = 1_000;

    public int getShortTxIdBytes() {
        return shortTxIdBytes;
    }

    public void setShortTxIdBytes(int shortTxIdBytes) {
        this.shortTxIdBytes = shortTxIdBytes;
    }

    public long getRebuildTimeoutMillis() {
        return rebuildTimeoutMillis;
    }

    public void setRebuildTimeoutMillis(long rebuildTimeoutMillis) {
        this.rebuildTimeoutMillis = rebuildTimeoutMillis;
    }

    public long getRequestTimeoutMillis() {
        return requestTimeoutMillis;
    }

    public void setRequestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    public int getFullBlockRetry() {
        return fullBlockRetry;
    }

    public void setFullBlockRetry(int fullBlockRetry) {
        this.fullBlockRetry = fullBlockRetry;
    }

    public int getMempoolMaxSize() {
        return mempoolMaxSize;
    }

    public void setMempoolMaxSize(int mempoolMaxSize) {
        this.mempoolMaxSize = mempoolMaxSize;
    }

    public int getSeenAnnouncementMaxSize() {
        return seenAnnouncementMaxSize;
    }

    public void setSeenAnnouncementMaxSize(int seenAnnouncementMaxSize) {
        this.seenAnnouncementMaxSize = seenAnnouncementMaxSize;
    }

    public int getOrphanMaxSize() {
        return orphanMaxSize;
    }

    public void setOrphanMaxSize(int orphanMaxSize) {
        this.orphanMaxSize = orphanMaxSize;
    }

    public int getKnownBlockMaxSize() {
        return knownBlockMaxSize;
    }

    public void setKnownBlockMaxSize(int knownBlockMaxSize) {
        this.knownBlockMaxSize = knownBlockMaxSize;
    }
}
