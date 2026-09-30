package com.github.guoyyit.compactblockrelayjava.model;

/**
 * 一笔交易：txid 为交易内容的唯一标识，data 为交易内容。
 */
public record Transaction(String txid, byte[] data) {
}
