package com.github.guoyyit.compactblockrelayjava.protocol;

import com.github.guoyyit.compactblockrelayjava.model.Transaction;

/**
 * 按交易在区块中的下标附带交易内容，避免短标识歧义。
 */
public record IndexedTx(int index, Transaction transaction) {
}
