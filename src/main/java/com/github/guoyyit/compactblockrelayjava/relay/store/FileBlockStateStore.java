package com.github.guoyyit.compactblockrelayjava.relay.store;

import com.github.guoyyit.compactblockrelayjava.relay.model.Block;
import com.github.guoyyit.compactblockrelayjava.relay.model.BlockHeader;
import com.github.guoyyit.compactblockrelayjava.relay.model.Transaction;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于追加日志文件的 BlockStateStore 实现（无外部依赖）。
 *
 * <p>日志记录类型：
 * <ul>
 *   <li>{@code P <hash> <height> <prev>}：进入未完成的紧凑重建</li>
 *   <li>{@code C <base64>}：区块已确认（含完整内容），同时终结同名 pending</li>
 *   <li>{@code X <hash>}：pending 取消（重建放弃/回退完成）</li>
 * </ul>
 * 每条记录追加后立即 fsync。重启重放日志后：pending 映射与 confirmed 集合天然分离，
 * 未完成重建不会被误判为已接收。
 */
public final class FileBlockStateStore implements BlockStateStore {

    private static final int DEFAULT_MAX_CONFIRMED = 1_000;

    private final Path logFile;
    private final int maxConfirmed;
    private final Map<String, Block> confirmed = new LinkedHashMap<>();
    private final Map<String, PendingBlock> pending = new LinkedHashMap<>();

    public FileBlockStateStore(Path dataDir) {
        this(dataDir, DEFAULT_MAX_CONFIRMED);
    }

    public FileBlockStateStore(Path dataDir, int maxConfirmed) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.logFile = dataDir.resolve("relay.log");
        this.maxConfirmed = maxConfirmed;
        replay();
    }

    @Override
    public synchronized void savePending(String blockHash, long height, String prevBlockHash) {
        if (confirmed.containsKey(blockHash) || pending.containsKey(blockHash)) {
            return;
        }
        append("P " + blockHash + " " + height + " " + (prevBlockHash == null ? "" : prevBlockHash));
        pending.put(blockHash, new PendingBlock(blockHash, height, prevBlockHash));
    }

    @Override
    public synchronized void markConfirmed(Block block) {
        String hash = block.getHeader().getBlockHash();
        if (confirmed.containsKey(hash)) {
            return;
        }
        append("C " + Base64.getEncoder().encodeToString(encodeBlock(block)));
        confirmed.put(hash, block);
        pending.remove(hash);
        trimConfirmed();
    }

    @Override
    public synchronized void removePending(String blockHash) {
        if (!pending.containsKey(blockHash)) {
            return;
        }
        append("X " + blockHash);
        pending.remove(blockHash);
    }

    @Override
    public synchronized List<PendingBlock> loadPending() {
        return new ArrayList<>(pending.values());
    }

    @Override
    public synchronized boolean isConfirmed(String blockHash) {
        return confirmed.containsKey(blockHash);
    }

    @Override
    public synchronized Block getConfirmed(String blockHash) {
        return confirmed.get(blockHash);
    }

    @Override
    public synchronized String getTipHash() {
        Block tip = tipBlock();
        return tip == null ? null : tip.getHeader().getBlockHash();
    }

    @Override
    public synchronized long getTipHeight() {
        Block tip = tipBlock();
        return tip == null ? -1L : tip.getHeader().getHeight();
    }

    private Block tipBlock() {
        Block tip = null;
        for (Block block : confirmed.values()) {
            if (tip == null || block.getHeader().getHeight() > tip.getHeader().getHeight()) {
                tip = block;
            }
        }
        return tip;
    }

    private void replay() {
        if (!Files.exists(logFile)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (String line : lines) {
            replayLine(line);
        }
        trimConfirmed();
    }

    private void replayLine(String line) {
        if (line.length() < 2) {
            return;
        }
        try {
            char type = line.charAt(0);
            String rest = line.substring(2);
            switch (type) {
                case 'P' -> {
                    String[] parts = rest.split(" ", 3);
                    if (parts.length == 3) {
                        String prev = parts[2].isEmpty() ? null : parts[2];
                        pending.put(parts[0],
                                new PendingBlock(parts[0], Long.parseLong(parts[1]), prev));
                    }
                }
                case 'C' -> {
                    Block block = decodeBlock(Base64.getDecoder().decode(rest));
                    confirmed.put(block.getHeader().getBlockHash(), block);
                    pending.remove(block.getHeader().getBlockHash());
                }
                case 'X' -> pending.remove(rest);
                default -> { }
            }
        } catch (RuntimeException ignored) {
            // 崩溃可能导致最后一行截断：跳过损坏行，前面已 fsync 的记录仍然有效
        }
    }

    private void trimConfirmed() {
        while (confirmed.size() > maxConfirmed) {
            String oldest = confirmed.keySet().iterator().next();
            confirmed.remove(oldest);
        }
    }

    private void append(String line) {
        try {
            Files.writeString(logFile, line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            try (FileOutputStream out = new FileOutputStream(logFile.toFile(), true)) {
                out.getFD().sync();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] encodeBlock(Block block) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            BlockHeader header = block.getHeader();
            out.writeUTF(header.getBlockHash());
            out.writeLong(header.getHeight());
            out.writeUTF(header.getPrevBlockHash() == null ? "" : header.getPrevBlockHash());
            out.writeInt(header.getTxIds().size());
            for (String txId : header.getTxIds()) {
                out.writeUTF(txId);
            }
            out.writeInt(block.getTransactions().size());
            for (Transaction tx : block.getTransactions()) {
                out.writeUTF(tx.getTxId());
                byte[] payload = tx.getPayload();
                out.writeInt(payload.length);
                out.write(payload);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static Block decodeBlock(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String hash = in.readUTF();
            long height = in.readLong();
            String prevRaw = in.readUTF();
            String prev = prevRaw.isEmpty() ? null : prevRaw;
            int txIdCount = in.readInt();
            List<String> txIds = new ArrayList<>(txIdCount);
            for (int i = 0; i < txIdCount; i++) {
                txIds.add(in.readUTF());
            }
            int txCount = in.readInt();
            List<Transaction> txs = new ArrayList<>(txCount);
            for (int i = 0; i < txCount; i++) {
                String txId = in.readUTF();
                int len = in.readInt();
                byte[] payload = new byte[len];
                in.readFully(payload);
                txs.add(new Transaction(txId, payload));
            }
            return new Block(new BlockHeader(hash, height, prev, txIds), txs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
