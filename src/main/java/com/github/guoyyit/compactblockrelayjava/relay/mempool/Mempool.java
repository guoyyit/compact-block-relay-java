package com.github.guoyyit.compactblockrelayjava.relay.mempool;

import com.github.guoyyit.compactblockrelayjava.relay.model.Transaction;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点本地交易池：容量有上界（超限按最久未使用淘汰）。
 */
public final class Mempool {

    private final int maxSize;
    private final Map<String, Transaction> transactions;

    public Mempool(int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
        }
        this.maxSize = maxSize;
        this.transactions = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Transaction> eldest) {
                return size() > Mempool.this.maxSize;
            }
        };
    }

    public synchronized void add(Transaction transaction) {
        transactions.putIfAbsent(transaction.getTxId(), transaction);
    }

    public synchronized Transaction get(String txId) {
        return transactions.get(txId);
    }

    public synchronized boolean contains(String txId) {
        return transactions.containsKey(txId);
    }

    public synchronized void remove(String txId) {
        transactions.remove(txId);
    }

    public synchronized int size() {
        return transactions.size();
    }

    public synchronized java.util.List<Transaction> snapshot() {
        return new java.util.ArrayList<>(transactions.values());
    }
}
