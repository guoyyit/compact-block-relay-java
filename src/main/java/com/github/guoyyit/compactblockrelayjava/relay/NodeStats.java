package com.github.guoyyit.compactblockrelayjava.relay;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 节点运行统计，供测试断言与观测使用。计数器仅由节点事件循环更新。
 */
public class NodeStats {
    private final AtomicInteger blocksAccepted = new AtomicInteger();
    private final AtomicInteger compactRebuilt = new AtomicInteger();
    private final AtomicInteger fullBlockFallbacks = new AtomicInteger();
    private final AtomicInteger txRequests = new AtomicInteger();
    private final AtomicInteger duplicateAnnouncementsDropped = new AtomicInteger();
    private final AtomicInteger staleAnnouncementsDropped = new AtomicInteger();
    private final AtomicInteger orphansHeld = new AtomicInteger();
    private final AtomicInteger blockRequests = new AtomicInteger();
    private final AtomicInteger fallbackRetries = new AtomicInteger();
    private final AtomicInteger fallbackReadmissions = new AtomicInteger();

    public int blocksAccepted() {
        return blocksAccepted.get();
    }

    public int compactRebuilt() {
        return compactRebuilt.get();
    }

    public int fullBlockFallbacks() {
        return fullBlockFallbacks.get();
    }

    public int txRequests() {
        return txRequests.get();
    }

    public int duplicateAnnouncementsDropped() {
        return duplicateAnnouncementsDropped.get();
    }

    public int staleAnnouncementsDropped() {
        return staleAnnouncementsDropped.get();
    }

    public int orphansHeld() {
        return orphansHeld.get();
    }

    public int blockRequests() {
        return blockRequests.get();
    }

    public int fallbackRetries() {
        return fallbackRetries.get();
    }

    public int fallbackReadmissions() {
        return fallbackReadmissions.get();
    }

    void incBlocksAccepted() {
        blocksAccepted.incrementAndGet();
    }

    void incCompactRebuilt() {
        compactRebuilt.incrementAndGet();
    }

    void incFullBlockFallbacks() {
        fullBlockFallbacks.incrementAndGet();
    }

    void incTxRequests() {
        txRequests.incrementAndGet();
    }

    void incDuplicateAnnouncementsDropped() {
        duplicateAnnouncementsDropped.incrementAndGet();
    }

    void incStaleAnnouncementsDropped() {
        staleAnnouncementsDropped.incrementAndGet();
    }

    void incOrphansHeld() {
        orphansHeld.incrementAndGet();
    }

    void incBlockRequests() {
        blockRequests.incrementAndGet();
    }

    void incFallbackRetries() {
        fallbackRetries.incrementAndGet();
    }

    void incFallbackReadmissions() {
        fallbackReadmissions.incrementAndGet();
    }
}
