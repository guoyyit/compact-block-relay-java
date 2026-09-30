package com.github.guoyyit.compactblockrelayjava.relay.node;

import com.github.guoyyit.compactblockrelayjava.relay.config.RelayConfig;
import com.github.guoyyit.compactblockrelayjava.relay.mempool.Mempool;
import com.github.guoyyit.compactblockrelayjava.relay.model.Block;
import com.github.guoyyit.compactblockrelayjava.relay.model.BlockHeader;
import com.github.guoyyit.compactblockrelayjava.relay.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.relay.network.Envelope;
import com.github.guoyyit.compactblockrelayjava.relay.network.LoopbackNetwork;
import com.github.guoyyit.compactblockrelayjava.relay.network.PeerHandler;
import com.github.guoyyit.compactblockrelayjava.relay.protocol.CompactBlockAnnouncement;
import com.github.guoyyit.compactblockrelayjava.relay.protocol.FullBlockRequest;
import com.github.guoyyit.compactblockrelayjava.relay.protocol.FullBlockResponse;
import com.github.guoyyit.compactblockrelayjava.relay.protocol.TxnRequest;
import com.github.guoyyit.compactblockrelayjava.relay.protocol.TxnResponse;
import com.github.guoyyit.compactblockrelayjava.relay.store.BlockStateStore;
import com.github.guoyyit.compactblockrelayjava.relay.util.BoundedLruCache;
import com.github.guoyyit.compactblockrelayjava.relay.util.ShortTxIdCalculator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.security.SecureRandom;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 区块网络节点：接收新交易、产生新区块、通过紧凑区块协议在回环网络上与对端交互。
 *
 * <p>并发模型：所有本地状态（会话、链、孤儿池）只在单一 {@link ReentrantLock} 下变更，
 * 网络 IO 在锁外执行；定时器触发时先抢锁再按会话当前阶段做幂等决策，
 * 因此“正常重建完成”与“超时回退”并发时结果确定：先到者推进阶段，后来者直接失效。
 */
public final class Node implements PeerHandler {

    private enum Phase { REBUILDING, FALLING_BACK }

    private record AnnouncementSource(CompactBlockAnnouncement announcement, String from) {
    }

    private static final class Session {
        private final String blockHash;
        private final BlockHeader header;
        private final long nonce;
        private final List<byte[]> shortTxIds;
        private final String[] resolved;
        private final boolean[] requested;
        private final String announcer;
        private Phase phase = Phase.REBUILDING;
        private ScheduledFuture<?> rebuildTimer;
        private ScheduledFuture<?> requestTimer;
        private int fullBlockAttempts;

        private Session(BlockHeader header, long nonce, List<byte[]> shortTxIds, String announcer) {
            this.blockHash = header.getBlockHash();
            this.header = header;
            this.nonce = nonce;
            this.shortTxIds = shortTxIds;
            this.announcer = announcer;
            int count = header.getTxIds().size();
            if (shortTxIds.size() != count) {
                throw new IllegalArgumentException(
                        "short id count mismatch for block " + header.getBlockHash());
            }
            this.resolved = new String[count];
            this.requested = new boolean[count];
        }
    }

    private final String nodeId;
    private final LoopbackNetwork network;
    private final BlockStateStore store;
    private final RelayConfig config;
    private final Mempool mempool;
    private final ShortTxIdCalculator shortTxIdCalculator;
    private final java.util.function.LongSupplier nonceGenerator;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition quiet = lock.newCondition();
    private int activeSessions;

    private final ScheduledExecutorService scheduler;

    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final BoundedLruCache<String, Boolean> seenAnnouncements;
    private final BoundedLruCache<String, AnnouncementSource> orphans;
    private final Map<String, Block> heldBlocks = new LinkedHashMap<>();

    public Node(String nodeId, LoopbackNetwork network, BlockStateStore store, RelayConfig config) {
        this(nodeId, network, store, config, new SecureRandom()::nextLong);
    }

    /** 测试辅助：可注入 nonce 来源（碰撞测试需要收发双方使用同一 nonce）。 */
    public Node(String nodeId, LoopbackNetwork network, BlockStateStore store, RelayConfig config,
                java.util.function.LongSupplier nonceGenerator) {
        this.nodeId = nodeId;
        this.network = network;
        this.store = store;
        this.config = config;
        this.mempool = new Mempool(config.getMempoolMaxSize());
        this.shortTxIdCalculator = new ShortTxIdCalculator(config.getShortTxIdBytes());
        this.seenAnnouncements = new BoundedLruCache<>(config.getSeenAnnouncementMaxSize());
        this.orphans = new BoundedLruCache<>(config.getOrphanMaxSize());
        this.nonceGenerator = nonceGenerator;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "relay-" + nodeId);
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        network.register(nodeId, this);
        lock.lock();
        try {
            // 崩溃恢复：未完成重建（pending）与已确认区块天然分离。
            // pending 不会被当作已接收；对端重启后重发通知时仍会重新重建，
            // store.savePending 幂等，已确认区块则直接丢弃，不会重复处理。
            store.loadPending().forEach(pending ->
                    seenAnnouncements.put(pending.blockHash(), Boolean.TRUE));
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    public String getNodeId() {
        return nodeId;
    }

    public Mempool getMempool() {
        return mempool;
    }

    /** 接收一笔新交易，放入本地交易池，并尝试推进在途重建。 */
    public void receiveTransaction(Transaction transaction) {
        mempool.add(transaction);
        lock.lock();
        try {
            for (Session session : List.copyOf(sessions.values())) {
                if (session.phase == Phase.REBUILDING) {
                    tryFillFromMempool(session);
                    tryFinish(session);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** 产生新区块：本地直接上链，再以紧凑区块通知广播。 */
    public void mineAndAnnounce(Block block) {
        String hash = block.getHeader().getBlockHash();
        long nonce = nonceGenerator.getAsLong();
        lock.lock();
        try {
            heldBlocks.put(hash, block);
            trimHeldBlocks();
            if (!store.isConfirmed(hash)) {
                applyBlock(block);
            }
        } finally {
            lock.unlock();
        }
        List<byte[]> shortIds = computeShortIds(block, nonce);
        broadcast(new CompactBlockAnnouncement(block.getHeader(), nonce, shortIds));
    }

    /** 测试辅助：向指定节点重发某已持有区块的紧凑通知。 */
    public void reannounceTo(Block block, String to) {
        long nonce = nonceGenerator.getAsLong();
        List<byte[]> shortIds = computeShortIds(block, nonce);
        network.send(new Envelope(nodeId, to,
                new CompactBlockAnnouncement(block.getHeader(), nonce, shortIds)));
    }

    @Override
    public void handle(Envelope envelope) {
        Object payload = envelope.getPayload();
        if (payload instanceof CompactBlockAnnouncement announcement) {
            onAnnouncement(envelope.getFrom(), announcement);
        } else if (payload instanceof TxnRequest request) {
            onTxnRequest(envelope.getFrom(), request);
        } else if (payload instanceof TxnResponse response) {
            onTxnResponse(response);
        } else if (payload instanceof FullBlockRequest request) {
            onFullBlockRequest(envelope.getFrom(), request);
        } else if (payload instanceof FullBlockResponse response) {
            onFullBlockResponse(response);
        }
    }

    /** 测试辅助：等待所有在途重建收敛。 */
    public void awaitQuiet(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        lock.lock();
        try {
            while (activeSessions > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                quiet.await(remaining, TimeUnit.NANOSECONDS);
            }
        } finally {
            lock.unlock();
        }
    }

    /** 测试辅助：当前链尖区块哈希。 */
    public String tipHash() {
        return store.getTipHash();
    }

    /** 测试辅助：当前链尖区块声明的交易完整标识列表（逐笔校验用）。 */
    public List<String> tipTxIds() {
        Block tip = store.getConfirmed(store.getTipHash());
        return tip == null ? List.of() : List.copyOf(tip.getHeader().getTxIds());
    }

    /** 测试辅助：当前保留的通知去重记录条数（必须有上界）。 */
    public int seenAnnouncementCount() {
        lock.lock();
        try {
            return seenAnnouncements.size();
        } finally {
            lock.unlock();
        }
    }

    /** 测试辅助：轮询等待条件成立（兼容消息尚在网络延迟中的情况）。 */
    public boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            lock.lock();
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } finally {
                lock.unlock();
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    // ---------------------------------------------------------------
    // 紧凑重建主流程
    // ---------------------------------------------------------------

    private void onAnnouncement(String from, CompactBlockAnnouncement announcement) {
        BlockHeader header = announcement.getHeader();
        String hash = header.getBlockHash();
        lock.lock();
        try {
            seenAnnouncements.put(hash, Boolean.TRUE);
            if (store.isConfirmed(hash) || sessions.containsKey(hash) || header.getHeight() <= store.getTipHeight()) {
                return;
            }
            orphans.put(hash, new AnnouncementSource(announcement, from));
            if (connectsToTip(header)) {
                startRebuild(hash);
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean connectsToTip(BlockHeader header) {
        String prev = header.getPrevBlockHash();
        return prev == null || store.isConfirmed(prev);
    }

    private void startRebuild(String hash) {
        AnnouncementSource source = orphans.get(hash);
        if (source == null || sessions.containsKey(hash)) {
            return;
        }
        CompactBlockAnnouncement announcement = source.announcement();
        BlockHeader header = announcement.getHeader();
        Session session;
        try {
            session = new Session(header, announcement.getNonce(),
                    announcement.getShortTxIds(), source.from());
        } catch (IllegalArgumentException malformed) {
            // 畸形通知：短标识数量与区块头声明不一致，安全丢弃，不进入重建
            orphans.remove(hash);
            return;
        }
        sessions.put(hash, session);
        activeSessions++;
        store.savePending(hash, header.getHeight(), header.getPrevBlockHash());
        scheduleRebuildTimer(session);
        rebuild(session);
    }

    private void rebuild(Session session) {
        buildShortIdIndex(session);
        tryFillFromMempool(session);
        tryRequestMissing(session);
        tryFinish(session);
    }

    /** 依据短标识从本地交易池预填；短标识命中多笔交易（碰撞）时一律按未命中处理。 */
    private void buildShortIdIndex(Session session) {
        Map<String, List<Transaction>> byShortId = new LinkedHashMap<>();
        // 索引覆盖整个本地交易池：按区块 nonce 对池中每笔交易计算短标识。
        // 区块内多笔声明交易的短标识命中同一池内交易（碰撞）时该槽位按未命中处理；
        // 任何被错误预填的交易最终都会被逐笔完整标识校验拦截并触发完整区块回退。
        for (Transaction tx : mempool.snapshot()) {
            byShortId.computeIfAbsent(
                    shortKey(shortTxIdCalculator.compute(tx.getTxId(), session.nonce)),
                    key -> new ArrayList<>()).add(tx);
        }
        for (int i = 0; i < session.resolved.length; i++) {
            if (session.resolved[i] == null) {
                List<Transaction> candidates = byShortId.get(shortKey(session.shortTxIds.get(i)));
                if (candidates != null && candidates.size() == 1) {
                    session.resolved[i] = candidates.get(0).getTxId();
                }
            }
        }
    }

    /** 新交易进入交易池后按完整标识精确补齐缺口，可安全消解短标识碰撞。 */
    private void tryFillFromMempool(Session session) {
        List<String> declared = session.header.getTxIds();
        for (int i = 0; i < session.resolved.length; i++) {
            if (session.resolved[i] == null && mempool.contains(declared.get(i))) {
                session.resolved[i] = declared.get(i);
            }
        }
    }

    /** 多个缺失交易合并为一次请求；响应到达前同一缺口绝不重复请求。 */
    private void tryRequestMissing(Session session) {
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < session.requested.length; i++) {
            if (session.resolved[i] == null && !session.requested[i]) {
                session.requested[i] = true;
                missing.add(session.header.getTxIds().get(i));
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        scheduleRequestTimer(session);
        network.send(new Envelope(nodeId, session.announcer,
                new TxnRequest(session.blockHash, missing)));
    }

    private void onTxnResponse(TxnResponse response) {
        lock.lock();
        try {
            Session session = sessions.get(response.getBlockHash());
            // 重复响应 / 迟到响应（区块已应用或已回退）安全丢弃
            if (session == null || session.phase != Phase.REBUILDING) {
                return;
            }
            for (Transaction tx : response.getTransactions()) {
                mempool.add(tx);
            }
            cancelRequestTimer(session);
            tryFillFromMempool(session);
            tryFinish(session);
        } finally {
            lock.unlock();
        }
    }

    /** 逐笔校验完整标识；任何不一致（碰撞误配/内容缺失）都触发完整区块回退。 */
    private void tryFinish(Session session) {
        if (session.phase != Phase.REBUILDING) {
            return;
        }
        List<String> declared = session.header.getTxIds();
        for (int i = 0; i < declared.size(); i++) {
            String resolved = session.resolved[i];
            if (resolved == null) {
                return;
            }
            if (!resolved.equals(declared.get(i)) || mempool.get(resolved) == null) {
                fallbackToFullBlock(session, false);
                return;
            }
        }
        List<Transaction> transactions = new ArrayList<>(declared.size());
        for (String txId : declared) {
            transactions.add(mempool.get(txId));
        }
        complete(session, new Block(session.header, transactions));
    }

    private void onFullBlockResponse(FullBlockResponse response) {
        lock.lock();
        try {
            Block block = response.getBlock();
            String hash = block.getHeader().getBlockHash();
            Session session = sessions.get(hash);
            // 迟到的完整区块响应：区块已落地则安全丢弃，不得重复应用
            if (session == null || store.isConfirmed(hash)) {
                return;
            }
            cancelRequestTimer(session);
            if (!structurallyValid(block)) {
                discard(session);
                return;
            }
            complete(session, block);
        } finally {
            lock.unlock();
        }
    }

    /** 幂等回退入口：重建超时、补齐超时、请求失败、校验失败全部汇聚于此。 */
    private void fallbackToFullBlock(Session session, boolean retryExhausted) {
        if (session.phase != Phase.REBUILDING
                && !(session.phase == Phase.FALLING_BACK && retryExhausted)) {
            return;
        }
        boolean exhausted = retryExhausted || session.fullBlockAttempts >= config.getFullBlockRetry() + 1;
        if (exhausted) {
            session.phase = Phase.FALLING_BACK;
            discard(session);
            return;
        }
        session.phase = Phase.FALLING_BACK;
        cancelRebuildTimer(session);
        cancelRequestTimer(session);
        session.fullBlockAttempts++;
        ScheduledFuture<?> timer = scheduler.schedule(() -> {
            lock.lock();
            try {
                Session current = sessions.get(session.blockHash);
                if (current != null && current.phase == Phase.FALLING_BACK) {
                    fallbackToFullBlock(current, true);
                }
            } finally {
                lock.unlock();
            }
        }, config.getRequestTimeoutMillis(), TimeUnit.MILLISECONDS);
        session.requestTimer = timer;
        network.send(new Envelope(nodeId, session.announcer,
                new FullBlockRequest(session.blockHash)));
    }

    private void complete(Session session, Block block) {
        cancelRebuildTimer(session);
        cancelRequestTimer(session);
        applyBlock(block);
        sessions.remove(session.blockHash);
        orphans.remove(session.blockHash);
        activeSessions--;
        quiet.signalAll();
        tryConnectOrphans();
    }

    private void discard(Session session) {
        cancelRebuildTimer(session);
        cancelRequestTimer(session);
        sessions.remove(session.blockHash);
        // 重建彻底失败：清除 pending 与去重记录。这样未完成重建不会被误判为已接收，
        // 且网络恢复后对端重发通知仍能重新处理并最终上链。
        store.removePending(session.blockHash);
        seenAnnouncements.remove(session.blockHash);
        activeSessions--;
        quiet.signalAll();
    }

    // ---------------------------------------------------------------
    // 定时器
    // ---------------------------------------------------------------

    private void scheduleRebuildTimer(Session session) {
        session.rebuildTimer = scheduler.schedule(() -> {
            lock.lock();
            try {
                Session current = sessions.get(session.blockHash);
                if (current != null && current.phase == Phase.REBUILDING) {
                    fallbackToFullBlock(current, false);
                }
            } finally {
                lock.unlock();
            }
        }, config.getRebuildTimeoutMillis(), TimeUnit.MILLISECONDS);
    }

    private void scheduleRequestTimer(Session session) {
        session.requestTimer = scheduler.schedule(() -> {
            lock.lock();
            try {
                Session current = sessions.get(session.blockHash);
                if (current != null && current.phase == Phase.REBUILDING) {
                    fallbackToFullBlock(current, false);
                }
            } finally {
                lock.unlock();
            }
        }, config.getRequestTimeoutMillis(), TimeUnit.MILLISECONDS);
    }

    private void cancelRebuildTimer(Session session) {
        if (session.rebuildTimer != null) {
            session.rebuildTimer.cancel(false);
            session.rebuildTimer = null;
        }
    }

    private void cancelRequestTimer(Session session) {
        if (session.requestTimer != null) {
            session.requestTimer.cancel(false);
            session.requestTimer = null;
        }
    }

    // ---------------------------------------------------------------
    // 对端请求的服务端处理（持有完整区块/交易的节点响应）
    // ---------------------------------------------------------------

    private void onTxnRequest(String from, TxnRequest request) {
        Block block = heldBlocks.get(request.getBlockHash());
        if (block == null) {
            // 本地没有该区块，无法按交易补齐：不回应，由对端超时回退到完整区块
            return;
        }
        Map<String, Transaction> byId = new LinkedHashMap<>();
        for (Transaction tx : block.getTransactions()) {
            byId.put(tx.getTxId(), tx);
        }
        List<Transaction> found = new ArrayList<>();
        for (String txId : request.getMissingTxIds()) {
            Transaction tx = byId.get(txId);
            if (tx == null) {
                tx = mempool.get(txId);
            }
            if (tx != null) {
                found.add(tx);
            }
        }
        network.send(new Envelope(nodeId, from, new TxnResponse(request.getBlockHash(), found)));
    }

    private void onFullBlockRequest(String from, FullBlockRequest request) {
        Block block = heldBlocks.get(request.getBlockHash());
        if (block == null) {
            block = store.getConfirmed(request.getBlockHash());
        }
        if (block != null) {
            network.send(new Envelope(nodeId, from, new FullBlockResponse(block)));
        }
    }

    // ---------------------------------------------------------------
    // 上链、孤儿与广播
    // ---------------------------------------------------------------

    private void applyBlock(Block block) {
        String hash = block.getHeader().getBlockHash();
        if (store.isConfirmed(hash)) {
            return;
        }
        if (!structurallyValid(block)) {
            throw new IllegalArgumentException("invalid block: " + hash);
        }
        store.markConfirmed(block);
        for (Transaction tx : block.getTransactions()) {
            mempool.remove(tx.getTxId());
        }
    }

    private boolean structurallyValid(Block block) {
        List<String> declared = block.getHeader().getTxIds();
        if (block.getTransactions().size() != declared.size()) {
            return false;
        }
        for (int i = 0; i < declared.size(); i++) {
            if (!declared.get(i).equals(block.getTransactions().get(i).getTxId())) {
                return false;
            }
        }
        return true;
    }

    private void trimHeldBlocks() {
        while (heldBlocks.size() > config.getKnownBlockMaxSize()) {
            String oldest = heldBlocks.keySet().iterator().next();
            heldBlocks.remove(oldest);
        }
    }

    private List<byte[]> computeShortIds(Block block, long nonce) {
        List<byte[]> result = new ArrayList<>(block.getHeader().getTxIds().size());
        for (String txId : block.getHeader().getTxIds()) {
            result.add(shortTxIdCalculator.compute(txId, nonce));
        }
        return result;
    }

    /** 父块上链后重放可连接的孤儿通知；不可连接的孤儿保留在有界缓存中，过期或超限安全丢弃。 */
    private void tryConnectOrphans() {
        List<String> connectable = new ArrayList<>();
        for (String hash : orphans.keys()) {
            AnnouncementSource source = orphans.get(hash);
            if (source == null || store.isConfirmed(hash) || sessions.containsKey(hash)) {
                continue;
            }
            String prev = source.announcement().getHeader().getPrevBlockHash();
            if (prev == null || store.isConfirmed(prev)) {
                connectable.add(hash);
            }
        }
        for (String hash : connectable) {
            startRebuild(hash);
        }
    }

    private void broadcast(Object payload) {
        for (String peer : network.nodeIds()) {
            if (!peer.equals(nodeId)) {
                network.send(new Envelope(nodeId, peer, payload));
            }
        }
    }

    private static String shortKey(byte[] shortTxId) {
        StringBuilder sb = new StringBuilder(shortTxId.length * 2);
        for (byte b : shortTxId) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
