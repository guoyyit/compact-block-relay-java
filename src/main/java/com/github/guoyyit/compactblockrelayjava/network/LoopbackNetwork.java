package com.github.guoyyit.compactblockrelayjava.network;

import com.github.guoyyit.compactblockrelayjava.protocol.Message;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * 本地回环网络：不依赖真实网络，节点间消息通过进程内投递模拟。
 *
 * <p>每个节点拥有独立的单线程事件循环，发往同一节点的消息严格串行处理，
 * 但不同节点之间可以并发推进，从而真实模拟“回退路径与正常路径并发”的场景。
 * 消息发给未注册（已崩溃/未启动）节点时直接丢弃；可注入失败规则模拟请求失败。
 */
public class LoopbackNetwork implements Network {
    private final Map<String, Registration> peers = new ConcurrentHashMap<>();
    private final AtomicInteger delivered = new AtomicInteger();
    private volatile Predicate<Message> dropRule = message -> false;

    @Override
    public void register(String id, Peer peer) {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "loopback-" + id);
            thread.setDaemon(true);
            return thread;
        });
        peers.put(id, new Registration(peer, executor));
    }

    @Override
    public void unregister(String id) {
        Registration registration = peers.remove(id);
        if (registration != null) {
            registration.executor().shutdownNow();
        }
    }

    @Override
    public void send(Message message) {
        if (dropRule.test(message)) {
            return;
        }
        Registration registration = peers.get(message.recipientId());
        if (registration == null) {
            return;
        }
        delivered.incrementAndGet();
        Peer peer = registration.peer();
        registration.executor().execute(() -> peer.receive(message));
    }

    @Override
    public void shutdown() {
        for (String id : peers.keySet()) {
            unregister(id);
        }
    }

    /**
     * 注入消息丢弃规则（模拟丢包/请求失败），规则返回 true 的消息不会投递。
     */
    public void setDropRule(Predicate<Message> rule) {
        this.dropRule = rule == null ? message -> false : rule;
    }

    public int deliveredCount() {
        return delivered.get();
    }

    private record Registration(Peer peer, ExecutorService executor) {
    }
}
