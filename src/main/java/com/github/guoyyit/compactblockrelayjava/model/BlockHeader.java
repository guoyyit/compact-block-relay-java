package com.github.guoyyit.compactblockrelayjava.model;

import java.util.List;

/**
 * 区块头：声明前一块哈希、高度、本块全部交易标识（顺序即区块内顺序）以及短标识随机数。
 */
public record BlockHeader(String hash, String prevHash, long height, List<String> txids, long shortIdNonce) {
}
