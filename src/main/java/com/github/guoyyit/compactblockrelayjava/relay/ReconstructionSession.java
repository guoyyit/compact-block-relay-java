package com.github.guoyyit.compactblockrelayjava.relay;

import com.github.guoyyit.compactblockrelayjava.model.BlockHeader;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单个区块的紧凑重建会话状态。仅由所属节点事件循环访问，无需加锁。
 */
public class ReconstructionSession {
    private final BlockHeader header;
    private final List<Long> shortIds;
    private final String peerId;
    private final long k0;
    private final long k1;
    private final long deadlineNanos;
    private final Transaction[] slots;
    private final Set<Integer> requested = new HashSet<>();
    private int attempts;
    private long nextRequestDeadlineNanos;
    private SessionState state = SessionState.IN_PROGRESS;

    public ReconstructionSession(BlockHeader header, List<Long> shortIds, String peerId,
                                 long k0, long k1, long deadlineNanos) {
        this.header = header;
        this.shortIds = List.copyOf(shortIds);
        this.peerId = peerId;
        this.k0 = k0;
        this.k1 = k1;
        this.deadlineNanos = deadlineNanos;
        this.slots = new Transaction[header.txids().size()];
    }

    public BlockHeader header() {
        return header;
    }

    public List<Long> shortIds() {
        return shortIds;
    }

    public String peerId() {
        return peerId;
    }

    public long k0() {
        return k0;
    }

    public long k1() {
        return k1;
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public SessionState state() {
        return state;
    }

    public int attempts() {
        return attempts;
    }

    public long nextRequestDeadlineNanos() {
        return nextRequestDeadlineNanos;
    }

    public boolean isInProgress() {
        return state == SessionState.IN_PROGRESS;
    }

    public void fill(int index, Transaction transaction) {
        if (state == SessionState.IN_PROGRESS) {
            slots[index] = transaction;
        }
    }

    public Transaction slot(int index) {
        return slots[index];
    }

    /**
     * 收集当前仍为空的槽位下标；一旦下标已请求过则不再重复请求。
     */
    public List<Integer> missingIndexes() {
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null && !requested.contains(i)) {
                missing.add(i);
            }
        }
        return missing;
    }

    public List<Integer> unfilledIndexes() {
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                missing.add(i);
            }
        }
        return missing;
    }

    public void markRequested(List<Integer> indexes, long requestDeadlineNanos) {
        requested.addAll(indexes);
        attempts++;
        this.nextRequestDeadlineNanos = requestDeadlineNanos;
    }

    /**
     * 补齐请求超时后释放请求标记，允许重试（受 attempts 上限约束）。
     */
    public void releaseTimedOutRequests() {
        requested.removeIf(index -> slots[index] == null);
    }

    public boolean isComplete() {
        for (Transaction slot : slots) {
            if (slot == null) {
                return false;
            }
        }
        return true;
    }

    public List<Transaction> transactions() {
        return List.of(slots);
    }

    public void complete() {
        state = SessionState.COMPLETED;
    }

    public void abort() {
        state = SessionState.ABORTED;
    }
}
