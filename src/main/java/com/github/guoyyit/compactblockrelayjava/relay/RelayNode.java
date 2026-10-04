package com.github.guoyyit.compactblockrelayjava.relay;

import com.github.guoyyit.compactblockrelayjava.crypto.Hashes;
import com.github.guoyyit.compactblockrelayjava.crypto.SipHash;
import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.model.BlockHeader;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;
import com.github.guoyyit.compactblockrelayjava.network.Network;
import com.github.guoyyit.compactblockrelayjava.network.Peer;
import com.github.guoyyit.compactblockrelayjava.pool.Mempool;
import com.github.guoyyit.compactblockrelayjava.protocol.AnnounceMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.BlockRequestMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.BlockResponseMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.IndexedTx;
import com.github.guoyyit.compactblockrelayjava.protocol.Message;
import com.github.guoyyit.compactblockrelayjava.protocol.TxRequestMessage;
import com.github.guoyyit.compactblockrelayjava.protocol.TxResponseMessage;
import com.github.guoyyit.compactblockrelayjava.storage.BlockRepository;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 紧凑区块传播节点。
 *
 * <p>所有状态变更都在节点专属单线程事件循环上串行执行：网络消息、本地 API 调用与
 * 超时事件最终都进入同一队列。因此“正常重建路径与完整区块回退路径并发发生”时
 * 结果确定：先到先处理；一旦区块已接收或会话已终止，迟到响应与重复通知全部安全丢弃。
 */
public class RelayNode implements Peer {
    private final String id;
    private final RelayConfig config;
    private final Network network;
    private final BlockRepository repository;
    private final Random random;
    private final Mempool mempool;
    private final NodeStats stats = new NodeStats();
    private final Set<String> peers = new HashSet<>();

    private final java.util.concurrent.ExecutorService eventLoop =
            Executors.newSingleThreadExecutor(this::newDaemonThread);
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(this::newDaemonThread);

    private final Map<String, Block> chain = new LinkedHashMap<>();
    private String tipHash;
    private long tipHeight = -1;

    private final Map<String, ReconstructionSession> sessions = new LinkedHashMap<>();
    private final Map<String, FallbackState> fallbacks = new LinkedHashMap<>();
    private final Deque<String> exhaustedFallbacks = new ArrayDeque<>();
    private final Set<String> exhaustedSet = new HashSet<>();
    private final Deque<String> seenBlockHashes = new ArrayDeque<>();
    private final Set<String> seenSet = new HashSet<>();
    private final Deque<AnnounceMessage> orphanAnnounces = new ArrayDeque<>();
    private final Deque<Block> orphanBlocks = new ArrayDeque<>();

    private volatile boolean running;

    public RelayNode(String id, RelayConfig config, Network network, BlockRepository repository) {
        this(id, config, network, repository, Clock.systemUTC());
    }

    public RelayNode(String id, RelayConfig config, Network network, BlockRepository repository, Clock clock) {
        this.id = id;
        this.config = config;
        this.network = network;
        this.repository = repository;
        this.random = new Random(0x5eedL ^ id.hashCode());
        this.mempool = new Mempool(config.maxMempool());
    }

    public RelayNode(String id, RelayConfig config, Network network, BlockRepository repository, Clock clock,
                     Mempool mempool) {
        this.id = id;
        this.config = config;
        this.network = network;
        this.repository = repository;
        this.random = new Random(0x5eedL ^ id.hashCode());
        this.mempool = mempool;
    }

    public void start() {
        runOnEventLoop(() -> {
            running = true;
            Block recovered = repository.loadTip();
            if (recovered != null) {
                connectRecoveredBlock(recovered);
            }
        });
    }

    public void connectPeer(String peerId) {
        runOnEventLoop(() -> peers.add(peerId));
    }

    /**
     * 本地产生/已持有完整区块：校验、持久化、接上链并向对等节点发紧凑通知。
     */
    public void announceBlock(Block block) {
        runOnEventLoop(() -> {
            if (isWellFormed(block) && !alreadyAccepted(block.header().hash())) {
                considerBlock(block, null);
            }
        });
    }

    public void submitTransaction(Transaction transaction) {
        runOnEventLoop(() -> {
            if (isValidTransaction(transaction)) {
                mempool.add(transaction);
                fillInProgressSessions(transaction);
            }
        });
    }

    /**
     * 新交易到达时，检查全部在途重建会话：txid 与短标识同时匹配的未填充槽位立即填入；
     * 若该短标识在交易池中存在多个候选（歧义），对应会话回退完整区块。
     * 同一笔交易可同时填入多个会话，各会话互不影响。
     */
    private void fillInProgressSessions(Transaction transaction) {
        List<ReconstructionSession> activeSessions = new ArrayList<>(sessions.values());
        List<ReconstructionSession> ambiguousSessions = new ArrayList<>();
        List<ReconstructionSession> completedSessions = new ArrayList<>();
        for (ReconstructionSession session : activeSessions) {
            if (!session.isInProgress()) {
                continue;
            }
            boolean ambiguous = false;
            boolean matched = false;
            for (int index = 0; index < session.header().txids().size(); index++) {
                if (session.slot(index) != null) {
                    continue;
                }
                long shortId = session.shortIds().get(index);
                // 只评估新交易短标识相关的槽位：其余槽位不受这笔交易影响，
                // 保持原有的缺失/歧义规则。
                if (SipHash.shortId(session.k0(), session.k1(), transaction.txid()) != shortId) {
                    continue;
                }
                Set<Transaction> candidates =
                        mempool.candidates(session.k0(), session.k1(), shortId);
                if (candidates.size() > 1) {
                    ambiguous = true;
                    break;
                }
                if (session.matchesUnfilled(index, transaction)) {
                    session.fill(index, transaction);
                    matched = true;
                }
            }
            if (ambiguous) {
                ambiguousSessions.add(session);
            } else if (matched && session.isComplete()) {
                completedSessions.add(session);
            }
        }
        for (ReconstructionSession session : ambiguousSessions) {
            if (session.isInProgress()) {
                requestFullBlock(session.header().hash(), session.peerId());
            }
        }
        // 所有会话的填充与歧义判定完成后再上链：先完成的会话可能从交易池移除
        // 被多个区块共享的交易，不能影响其他会话的独立填充。
        for (ReconstructionSession session : completedSessions) {
            if (session.isInProgress() && session.isComplete()) {
                tryComplete(session);
            }
        }
    }

    public boolean hasBlock(String blockHash) {
        return awaitResult(() -> chain.containsKey(blockHash));
    }

    public long chainHeight() {
        return awaitResult(() -> tipHeight);
    }

    public int mempoolSize() {
        return awaitResult(mempool::size);
    }

    public NodeStats stats() {
        return stats;
    }

    public String nodeId() {
        return id;
    }

    public void shutdown() {
        running = false;
        timer.shutdownNow();
        eventLoop.shutdownNow();
    }

    @Override
    public void receive(Message message) {
        runOnEventLoop(() -> dispatch(message));
    }

    private void dispatch(Message message) {
        if (!running) {
            return;
        }
        if (message instanceof AnnounceMessage announce) {
            handleAnnounce(announce);
        } else if (message instanceof TxRequestMessage request) {
            handleTxRequest(request);
        } else if (message instanceof TxResponseMessage response) {
            handleTxResponse(response);
        } else if (message instanceof BlockRequestMessage request) {
            handleBlockRequest(request);
        } else if (message instanceof BlockResponseMessage response) {
            handleBlockResponse(response);
        }
    }

    private void handleAnnounce(AnnounceMessage message) {
        BlockHeader header = message.header();
        if (alreadyAccepted(header.hash())) {
            stats.incDuplicateAnnouncementsDropped();
            return;
        }
        boolean reaccepted = exhaustedSet.contains(header.hash());
        if (reaccepted) {
            stats.incReacceptedAnnouncements();
        }
        boolean newHash = reaccepted || rememberSeen(header.hash());
        if (sessions.containsKey(header.hash()) || fallbacks.containsKey(header.hash())
                || isHeldOrphan(header.hash()) || !newHash) {
            stats.incDuplicateAnnouncementsDropped();
            return;
        }
        if (header.txids().size() != message.shortIds().size() || isStaleHeight(header)) {
            stats.incStaleAnnouncementsDropped();
            return;
        }
        if (tipHash != null && !tipHash.equals(header.prevHash())) {
            holdOrphan(message);
            return;
        }
        beginSession(header, message.shortIds(), message.senderId(), message.k0(), message.k1());
    }

    private boolean isHeldOrphan(String blockHash) {
        for (AnnounceMessage announce : orphanAnnounces) {
            if (announce.header().hash().equals(blockHash)) {
                return true;
            }
        }
        for (Block block : orphanBlocks) {
            if (block.header().hash().equals(blockHash)) {
                return true;
            }
        }
        return false;
    }

    private void beginSession(BlockHeader header, List<Long> shortIds, String peerId, long k0, long k1) {
        if (fallbacks.containsKey(header.hash())) {
            return;
        }
        ReconstructionSession session = new ReconstructionSession(header, shortIds, peerId, k0, k1,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.rebuildTimeoutMs()));
        sessions.put(header.hash(), session);
        scheduleTimer(() -> onRebuildTimeout(header.hash()), config.rebuildTimeoutMs());

        boolean ambiguous = false;
        for (int index = 0; index < shortIds.size(); index++) {
            Set<Transaction> candidates = mempool.candidates(k0, k1, shortIds.get(index));
            if (candidates.size() == 1) {
                session.fill(index, candidates.iterator().next());
            } else if (candidates.size() > 1) {
                ambiguous = true;
            }
        }
        if (ambiguous) {
            requestFullBlock(header.hash(), peerId);
            return;
        }
        List<Integer> missing = session.missingIndexes();
        if (missing.isEmpty()) {
            tryComplete(session);
        } else {
            sendTxRequest(session, missing);
        }
    }

    private void handleTxRequest(TxRequestMessage message) {
        Block block = chain.get(message.blockHash());
        if (block == null) {
            return;
        }
        List<IndexedTx> response = new ArrayList<>();
        for (int index : message.indexes()) {
            if (index >= 0 && index < block.transactions().size()) {
                response.add(new IndexedTx(index, block.transactions().get(index)));
            }
        }
        network.send(new TxResponseMessage(id, message.senderId(), message.blockHash(), response));
    }

    private void handleTxResponse(TxResponseMessage message) {
        ReconstructionSession session = sessions.get(message.blockHash());
        if (session == null || !session.isInProgress()) {
            return;
        }
        for (IndexedTx indexed : message.transactions()) {
            int index = indexed.index();
            if (index < 0 || index >= session.header().txids().size() || session.slot(index) != null) {
                continue;
            }
            Transaction tx = indexed.transaction();
            if (tx == null || !session.header().txids().get(index).equals(tx.txid())
                    || !isValidTransaction(tx)) {
                requestFullBlock(session.header().hash(), session.peerId());
                return;
            }
            session.fill(index, tx);
        }
        if (session.isComplete()) {
            tryComplete(session);
        }
    }

    private void handleBlockRequest(BlockRequestMessage message) {
        Block block = chain.get(message.blockHash());
        if (block != null) {
            network.send(new BlockResponseMessage(id, message.senderId(), block));
        }
    }

    private void handleBlockResponse(BlockResponseMessage message) {
        Block block = message.block();
        if (block == null || alreadyAccepted(block.header().hash()) || !isWellFormed(block)) {
            return;
        }
        terminateSession(block.header().hash());
        clearFallback(block.header().hash());
        considerBlock(block, message.senderId());
    }

    private void sendTxRequest(ReconstructionSession session, List<Integer> indexes) {
        if (indexes.isEmpty()) {
            return;
        }
        session.markRequested(indexes, System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(config.txRequestTimeoutMs()));
        stats.incTxRequests();
        network.send(new TxRequestMessage(id, session.peerId(),
                session.header().hash(), List.copyOf(indexes)));
        int attempt = session.attempts();
        scheduleTimer(() -> onTxRequestTimeout(session.header().hash(), attempt),
                config.txRequestTimeoutMs());
    }

    private void requestFullBlock(String blockHash, String peerId) {
        ReconstructionSession session = sessions.get(blockHash);
        if (session != null && session.isInProgress()) {
            session.abort();
            stats.incFullBlockFallbacks();
        }
        FallbackState state = fallbacks.get(blockHash);
        if (state == null) {
            state = new FallbackState(peerId);
            fallbacks.put(blockHash, state);
        }
        sendBlockRequest(blockHash, state);
    }

    private void sendBlockRequest(String blockHash, FallbackState state) {
        exhaustedSet.remove(blockHash);
        exhaustedFallbacks.removeIf(blockHash::equals);
        if (state.requestsSent() > 0) {
            stats.incFallbackRetries();
        }
        stats.incBlockRequests();
        long token = state.requestSent();
        network.send(new BlockRequestMessage(id, state.peerId(), blockHash));
        scheduleTimer(() -> onFallbackTimeout(blockHash, token),
                config.fallbackRequestTimeoutMs());
    }

    private void onFallbackTimeout(String blockHash, long token) {
        runOnEventLoop(() -> {
            FallbackState state = fallbacks.get(blockHash);
            if (state == null || state.inFlightToken() != token) {
                return;
            }
            if (alreadyAccepted(blockHash)) {
                clearFallback(blockHash);
                return;
            }
            if (state.requestsSent() <= config.maxFallbackRetries()) {
                sendBlockRequest(blockHash, state);
            } else {
                giveUpFallback(blockHash);
            }
        });
    }

    /**
     * 回退重试耗尽：释放该区块的全部进行中占位与去重窗口记录，
     * 使后续重新到达的同一区块通知能重新进入重建或回退流程。
     */
    private void giveUpFallback(String blockHash) {
        terminateSession(blockHash);
        fallbacks.remove(blockHash);
        rememberExhausted(blockHash);
        stats.incFallbackExhausted();
    }

    private void clearFallback(String blockHash) {
        fallbacks.remove(blockHash);
        exhaustedSet.remove(blockHash);
        exhaustedFallbacks.removeIf(blockHash::equals);
    }

    /**
     * 有上界的“回退失败待重新接纳”哈希记录（LRU）。加入时同时将该哈希移出普通
     * 去重窗口，使下一份通知不被当作重复通知丢弃。
     */
    private void rememberExhausted(String blockHash) {
        seenBlockHashes.removeIf(blockHash::equals);
        seenSet.remove(blockHash);
        if (exhaustedSet.add(blockHash)) {
            exhaustedFallbacks.addLast(blockHash);
            while (exhaustedFallbacks.size() > config.maxSeenHashes()) {
                String oldest = exhaustedFallbacks.removeFirst();
                exhaustedSet.remove(oldest);
            }
        }
    }

    private void onRebuildTimeout(String blockHash) {
        runOnEventLoop(() -> {
            ReconstructionSession session = sessions.get(blockHash);
            if (session != null && session.isInProgress()) {
                requestFullBlock(blockHash, session.peerId());
            }
        });
    }

    private void onTxRequestTimeout(String blockHash, int attempt) {
        runOnEventLoop(() -> {
            ReconstructionSession session = sessions.get(blockHash);
            if (session == null || !session.isInProgress() || session.attempts() != attempt) {
                return;
            }
            if (session.isComplete()) {
                tryComplete(session);
                return;
            }
            session.releaseTimedOutRequests();
            List<Integer> missing = session.missingIndexes();
            if (missing.isEmpty() || session.attempts() > config.maxRequestRetries()) {
                requestFullBlock(blockHash, session.peerId());
                return;
            }
            sendTxRequest(session, missing);
        });
    }

    private void tryComplete(ReconstructionSession session) {
        BlockHeader header = session.header();
        List<Transaction> transactions = session.transactions();
        for (int i = 0; i < header.txids().size(); i++) {
            Transaction tx = transactions.get(i);
            if (tx == null
                    || !header.txids().get(i).equals(tx.txid())
                    || !isValidTransaction(tx)
                    || SipHash.shortId(session.k0(), session.k1(), tx.txid()) != session.shortIds().get(i)) {
                requestFullBlock(header.hash(), session.peerId());
                return;
            }
        }
        session.complete();
        sessions.remove(header.hash());
        stats.incCompactRebuilt();
        Block rebuilt = new Block(header, transactions);
        if (!alreadyAccepted(header.hash()) && !isHeldOrphan(header.hash())) {
            considerBlock(rebuilt, session.peerId());
        }
    }

    private void considerBlock(Block block, String receivedFromPeerId) {
        BlockHeader header = block.header();
        if (alreadyAccepted(header.hash())) {
            return;
        }
        if (tipHash != null && !tipHash.equals(header.prevHash())) {
            if (!isStaleHeight(header)) {
                holdOrphanBlock(block);
            }
            return;
        }
        connectBlock(block, receivedFromPeerId);
        drainOrphans();
    }

    private void connectBlock(Block block, String receivedFromPeerId) {
        BlockHeader header = block.header();
        repository.save(block);
        chain.put(header.hash(), block);
        tipHash = header.hash();
        tipHeight = header.height();
        mempool.removeAll(header.txids());
        terminateSession(header.hash());
        clearFallback(header.hash());
        stats.incBlocksAccepted();
        propagateAnnouncement(block, receivedFromPeerId);
    }

    private void connectRecoveredBlock(Block block) {
        BlockHeader header = block.header();
        chain.put(header.hash(), block);
        tipHash = header.hash();
        tipHeight = header.height();
        mempool.removeAll(header.txids());
        stats.incBlocksAccepted();
    }

    private void propagateAnnouncement(Block block, String excludePeerId) {
        BlockHeader header = block.header();
        long k0 = random.nextLong();
        long k1 = random.nextLong();
        List<Long> shortIds = header.txids().stream()
                .map(txid -> SipHash.shortId(k0, k1, txid))
                .toList();
        for (String peerId : peers) {
            if (!peerId.equals(id) && !peerId.equals(excludePeerId)) {
                network.send(new AnnounceMessage(id, peerId, header, shortIds, k0, k1));
            }
        }
    }

    private void drainOrphans() {
        boolean progressed = true;
        while (progressed) {
            progressed = drainOrphanBlocks() || drainOrphanAnnounces();
        }
    }

    private boolean drainOrphanBlocks() {
        Iterator<Block> iterator = orphanBlocks.iterator();
        while (iterator.hasNext()) {
            Block block = iterator.next();
            if (tipHash != null && tipHash.equals(block.header().prevHash())) {
                iterator.remove();
                connectBlock(block, null);
                return true;
            }
        }
        return false;
    }

    private boolean drainOrphanAnnounces() {
        Iterator<AnnounceMessage> iterator = orphanAnnounces.iterator();
        while (iterator.hasNext()) {
            AnnounceMessage message = iterator.next();
            if (tipHash != null && tipHash.equals(message.header().prevHash())) {
                iterator.remove();
                beginSession(message.header(), message.shortIds(), message.senderId(),
                        message.k0(), message.k1());
                return true;
            }
        }
        return false;
    }

    private void holdOrphan(AnnounceMessage message) {
        for (AnnounceMessage existing : orphanAnnounces) {
            if (existing.header().hash().equals(message.header().hash())) {
                return;
            }
        }
        orphanAnnounces.addLast(message);
        stats.incOrphansHeld();
        while (orphanAnnounces.size() > config.maxOrphans()) {
            orphanAnnounces.removeFirst();
        }
    }

    private void holdOrphanBlock(Block block) {
        for (Block existing : orphanBlocks) {
            if (existing.header().hash().equals(block.header().hash())) {
                return;
            }
        }
        orphanBlocks.addLast(block);
        while (orphanBlocks.size() > config.maxOrphans()) {
            orphanBlocks.removeFirst();
        }
    }

    private void terminateSession(String blockHash) {
        ReconstructionSession session = sessions.remove(blockHash);
        if (session != null && session.isInProgress()) {
            session.abort();
        }
    }

    private boolean alreadyAccepted(String blockHash) {
        return repository.contains(blockHash) || chain.containsKey(blockHash);
    }

    /**
     * 有上界的区块哈希去重记录（LRU）。返回 false 表示该哈希在当前窗口内重复出现。
     */
    private boolean rememberSeen(String blockHash) {
        if (!seenSet.add(blockHash)) {
            return false;
        }
        seenBlockHashes.addLast(blockHash);
        while (seenBlockHashes.size() > config.maxSeenHashes()) {
            String oldest = seenBlockHashes.removeFirst();
            seenSet.remove(oldest);
        }
        return true;
    }

    private boolean isStaleHeight(BlockHeader header) {
        return tipHeight >= 0 && header.height() <= tipHeight;
    }

    private boolean isValidTransaction(Transaction transaction) {
        return transaction != null
                && transaction.txid() != null
                && transaction.data() != null
                && transaction.txid().equals(Hashes.sha256Hex(transaction.data()));
    }

    private boolean isWellFormed(Block block) {
        if (block == null) {
            return false;
        }
        BlockHeader header = block.header();
        if (header == null || header.txids().size() != block.transactions().size()) {
            return false;
        }
        for (int i = 0; i < header.txids().size(); i++) {
            Transaction tx = block.transactions().get(i);
            if (!isValidTransaction(tx) || !header.txids().get(i).equals(tx.txid())) {
                return false;
            }
        }
        return true;
    }

    private void scheduleTimer(Runnable event, long delayMs) {
        try {
            timer.schedule(() -> runOnEventLoop(event), Math.max(1, delayMs), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // 节点关闭后不再调度。
        }
    }

    private void runOnEventLoop(Runnable action) {
        try {
            eventLoop.execute(action);
        } catch (RejectedExecutionException ignored) {
            // 节点已关闭，事件丢弃。
        }
    }

    private <T> T awaitResult(java.util.function.Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        runOnEventLoop(() -> {
            try {
                future.complete(supplier.get());
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("node query failed", e);
        }
    }

    private Thread newDaemonThread(Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        return thread;
    }
}
