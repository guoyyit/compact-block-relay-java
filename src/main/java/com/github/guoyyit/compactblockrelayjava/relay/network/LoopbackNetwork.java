package com.github.guoyyit.compactblockrelayjava.relay.network;

import java.util.Map;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地回环网络：节点全部运行在同一进程内，通过该组件投递消息，不依赖真实网络。
 * 支持注入固定延迟与随机丢包，便于测试超时与请求失败回退。
 * 使用单线程调度器投递，保证全局消息按提交顺序到达。
 */
public final class LoopbackNetwork {

    private final Map<String, PeerHandler> handlers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "loopback-network");
                t.setDaemon(true);
                return t;
            });
    private final Random random;

    private volatile long delayMillis;
    private volatile double dropProbability;
    private final AtomicLong droppedCount = new AtomicLong();
    private final List<Predicate<Envelope>> filters = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicLong> sentByType = new ConcurrentHashMap<>();

    public LoopbackNetwork(long delayMillis, double dropProbability) {
        this(delayMillis, dropProbability, new Random());
    }

    public LoopbackNetwork(long delayMillis, double dropProbability, Random random) {
        this.delayMillis = delayMillis;
        this.dropProbability = dropProbability;
        this.random = random;
    }

    public void setDelayMillis(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    public void setDropProbability(double dropProbability) {
        this.dropProbability = dropProbability;
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }

    /** 测试辅助：按消息简单名统计成功提交的发送次数（不含被丢弃的消息）。 */
    public long sentCount(Class<?> messageType, String toNodeId) {
        AtomicLong counter = sentByType.get(key(messageType, toNodeId));
        return counter == null ? 0L : counter.get();
    }

    public void resetStats() {
        droppedCount.set(0L);
        sentByType.clear();
    }

    /** 测试辅助：增加丢弃过滤规则（返回 true 的消息直接丢弃，模拟请求失败）。 */
    public void addFilter(Predicate<Envelope> filter) {
        filters.add(filter);
    }

    public void clearFilters() {
        filters.clear();
    }

    private static String key(Class<?> messageType, String toNodeId) {
        return messageType.getSimpleName() + "#" + toNodeId;
    }

    public void register(String nodeId, PeerHandler handler) {
        handlers.put(nodeId, handler);
    }

    /** 当前已注册的全部节点 id（用于广播）。 */
    public java.util.Set<String> nodeIds() {
        return java.util.Set.copyOf(handlers.keySet());
    }

    public void send(Envelope envelope) {
        if (random.nextDouble() < dropProbability || filters.stream().anyMatch(f -> f.test(envelope))) {
            droppedCount.incrementAndGet();
            return;
        }
        PeerHandler handler = handlers.get(envelope.getTo());
        if (handler == null) {
            droppedCount.incrementAndGet();
            return;
        }
        sentByType.computeIfAbsent(key(envelope.getPayload().getClass(), envelope.getTo()),
                k -> new AtomicLong()).incrementAndGet();
        executor.schedule(() -> {
            PeerHandler current = handlers.get(envelope.getTo());
            if (current != null) {
                current.handle(envelope);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
