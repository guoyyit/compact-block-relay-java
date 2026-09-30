package com.github.guoyyit.compactblockrelayjava.storage;

import com.github.guoyyit.compactblockrelayjava.crypto.Hashes;
import com.github.guoyyit.compactblockrelayjava.model.Block;
import com.github.guoyyit.compactblockrelayjava.model.BlockHeader;
import com.github.guoyyit.compactblockrelayjava.model.Transaction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于追加日志文件的区块存储。
 *
 * <p>磁盘格式（每行一条，长度前缀防止内容注入）：
 * <pre>
 * H &lt;hash&gt; &lt;prevHash&gt; &lt;height&gt; &lt;nonce&gt; &lt;txidCount&gt;
 * X &lt;txid&gt; &lt;base64 data&gt;   （每个区块恰好 txidCount 行，顺序即区块顺序）
 * </pre>
 * 仅当一个区块的 H 行与全部 X 行完整落盘、且头中声明 txid 与交易完全一致时才算已确认；
 * 崩溃导致的不完整尾部记录在重放时被丢弃，因此未完成重建永远不会被误判为已接收。
 */
public class FileBlockRepository implements BlockRepository {
    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private final Path file;
    private final Map<String, Block> blocks = new LinkedHashMap<>();
    private final Set<String> hashes = new HashSet<>();
    private Block tip;

    public FileBlockRepository(Path file) {
        this.file = file;
        replay();
    }

    @Override
    public synchronized boolean contains(String blockHash) {
        return hashes.contains(blockHash);
    }

    @Override
    public synchronized void save(Block block) {
        if (!isWellFormed(block)) {
            throw new IllegalArgumentException("block does not match its header");
        }
        if (hashes.contains(block.header().hash())) {
            return;
        }
        StringBuilder record = new StringBuilder();
        BlockHeader header = block.header();
        record.append("H ").append(header.hash()).append(' ')
                .append(header.prevHash()).append(' ')
                .append(header.height()).append(' ')
                .append(header.shortIdNonce()).append(' ')
                .append(header.txids().size()).append('\n');
        for (Transaction tx : block.transactions()) {
            record.append("X ").append(tx.txid()).append(' ')
                    .append(ENCODER.encodeToString(tx.data())).append('\n');
        }
        try {
            Files.writeString(file, record.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist block " + header.hash(), e);
        }
        hashes.add(header.hash());
        blocks.put(header.hash(), block);
        tip = block;
    }

    @Override
    public synchronized Block loadTip() {
        return tip;
    }

    @Override
    public synchronized void close() {
    }

    private void replay() {
        if (!Files.exists(file)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read block log " + file, e);
        }
        int index = 0;
        int lastGoodLine = 0;
        while (index < lines.size()) {
            String headerLine = lines.get(index);
            String[] parts = headerLine.split(" ", 6);
            if (parts.length != 6 || !"H".equals(parts[0])) {
                break;
            }
            int txCount;
            try {
                txCount = Integer.parseInt(parts[5]);
            } catch (NumberFormatException e) {
                break;
            }
            if (index + 1 + txCount > lines.size()) {
                break;
            }
            List<Transaction> transactions = new ArrayList<>(txCount);
            List<String> txids = new ArrayList<>(txCount);
            boolean complete = true;
            for (int i = 0; i < txCount; i++) {
                String[] txParts = lines.get(index + 1 + i).split(" ", 3);
                if (txParts.length != 3 || !"X".equals(txParts[0])) {
                    complete = false;
                    break;
                }
                byte[] data;
                try {
                    data = DECODER.decode(txParts[2]);
                } catch (IllegalArgumentException e) {
                    complete = false;
                    break;
                }
                Transaction tx = new Transaction(txParts[1], data);
                if (!tx.txid().equals(Hashes.sha256Hex(tx.data()))) {
                    complete = false;
                    break;
                }
                transactions.add(tx);
                txids.add(tx.txid());
            }
            if (!complete) {
                break;
            }
            long height;
            long nonce;
            try {
                height = Long.parseLong(parts[3]);
                nonce = Long.parseLong(parts[4]);
            } catch (NumberFormatException e) {
                break;
            }
            BlockHeader header = new BlockHeader(parts[1], parts[2], height, List.copyOf(txids), nonce);
            Block block = new Block(header, List.copyOf(transactions));
            if (!isWellFormed(block) || hashes.contains(header.hash())) {
                break;
            }
            hashes.add(header.hash());
            blocks.put(header.hash(), block);
            tip = block;
            index += 1 + txCount;
            lastGoodLine = index;
        }
        if (index < lines.size()) {
            truncateAfter(lastGoodLine);
        }
    }

    private void truncateAfter(int goodLines) {
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            StringBuilder kept = new StringBuilder();
            for (int i = 0; i < goodLines && i < all.size(); i++) {
                kept.append(all.get(i)).append('\n');
            }
            Files.writeString(file, kept.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to truncate partial block log " + file, e);
        }
    }

    private static boolean isWellFormed(Block block) {
        BlockHeader header = block.header();
        if (header.txids().size() != block.transactions().size()) {
            return false;
        }
        for (int i = 0; i < header.txids().size(); i++) {
            Transaction tx = block.transactions().get(i);
            if (!header.txids().get(i).equals(tx.txid())) {
                return false;
            }
            if (!tx.txid().equals(Hashes.sha256Hex(tx.data()))) {
                return false;
            }
        }
        return true;
    }
}
