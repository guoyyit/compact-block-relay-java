package com.github.guoyyit.compactblockrelayjava.pool;

import com.github.guoyyit.compactblockrelayjava.crypto.SipHash;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 有上界的本地交易池。
 *
 * <p>容量达到上限时淘汰最早进入的交易（FIFO），保证交易池增长有界。
 * 按短标识查找可能返回多个候选（短标识碰撞），调用方必须处理歧义。
 * 所有方法仅由所属节点的事件循环单线程调用。
 */
public class Mempool {
    private final int maxSize;
    private final Map<String, Transaction> transactions = new LinkedHashMap<>();

    public Mempool(int maxSize) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        this.maxSize = maxSize;
    }

    public void add(Transaction transaction) {
        if (transactions.containsKey(transaction.txid())) {
            return;
        }
        transactions.put(transaction.txid(), transaction);
        while (transactions.size() > maxSize) {
            String oldest = transactions.keySet().iterator().next();
            transactions.remove(oldest);
        }
    }

    public Transaction get(String txid) {
        return transactions.get(txid);
    }

    public void removeAll(Collection<String> txids) {
        for (String txid : txids) {
            transactions.remove(txid);
        }
    }

    public int size() {
        return transactions.size();
    }

    /**
     * 返回池中短标识等于 shortId 的全部候选交易；无碰撞时集合大小为 1。
     */
    public Set<Transaction> candidates(long k0, long k1, long shortId) {
        Set<Transaction> matches = new LinkedHashSet<>();
        for (Transaction transaction : transactions.values()) {
            if (SipHash.shortId(k0, k1, transaction.txid()) == shortId) {
                matches.add(transaction);
            }
        }
        return Collections.unmodifiableSet(matches);
    }
}
