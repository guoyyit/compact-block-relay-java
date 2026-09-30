package com.github.guoyyit.compactblockrelayjava.relay.util;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 有上界的 LRU 缓存，用于通知去重记录等场景。
 */
public final class BoundedLruCache<K, V> {

    private final int maxSize;
    private final Map<K, V> map;

    public BoundedLruCache(int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be positive: " + maxSize);
        }
        this.maxSize = maxSize;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > BoundedLruCache.this.maxSize;
            }
        };
    }

    public synchronized V get(K key) {
        return map.get(key);
    }

    public synchronized void put(K key, V value) {
        map.put(key, value);
    }

    public synchronized boolean containsKey(K key) {
        return map.containsKey(key);
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized List<K> keys() {
        return new ArrayList<>(map.keySet());
    }

    public synchronized V remove(K key) {
        return map.remove(key);
    }
}
