package com.github.guoyyit.compactblockrelayjava.config;

import com.github.guoyyit.compactblockrelayjava.relay.RelayConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 紧凑区块传播可调参数（application.properties 前缀 compact-relay）。
 */
@ConfigurationProperties(prefix = "compact-relay")
public class RelayProperties {
    private int shortIdBytes = 8;
    private long rebuildTimeoutMs = 2_000;
    private long txRequestTimeoutMs = 500;
    private int maxMempool = 10_000;
    private int maxSeenHashes = 4_096;
    private int maxOrphans = 64;
    private int maxRequestRetries = 1;

    public RelayConfig toConfig() {
        return new RelayConfig(shortIdBytes, rebuildTimeoutMs, txRequestTimeoutMs,
                maxMempool, maxSeenHashes, maxOrphans, maxRequestRetries);
    }

    public int getShortIdBytes() {
        return shortIdBytes;
    }

    public void setShortIdBytes(int shortIdBytes) {
        this.shortIdBytes = shortIdBytes;
    }

    public long getRebuildTimeoutMs() {
        return rebuildTimeoutMs;
    }

    public void setRebuildTimeoutMs(long rebuildTimeoutMs) {
        this.rebuildTimeoutMs = rebuildTimeoutMs;
    }

    public long getTxRequestTimeoutMs() {
        return txRequestTimeoutMs;
    }

    public void setTxRequestTimeoutMs(long txRequestTimeoutMs) {
        this.txRequestTimeoutMs = txRequestTimeoutMs;
    }

    public int getMaxMempool() {
        return maxMempool;
    }

    public void setMaxMempool(int maxMempool) {
        this.maxMempool = maxMempool;
    }

    public int getMaxSeenHashes() {
        return maxSeenHashes;
    }

    public void setMaxSeenHashes(int maxSeenHashes) {
        this.maxSeenHashes = maxSeenHashes;
    }

    public int getMaxOrphans() {
        return maxOrphans;
    }

    public void setMaxOrphans(int maxOrphans) {
        this.maxOrphans = maxOrphans;
    }

    public int getMaxRequestRetries() {
        return maxRequestRetries;
    }

    public void setMaxRequestRetries(int maxRequestRetries) {
        this.maxRequestRetries = maxRequestRetries;
    }
}
