package com.github.guoyyit.compactblockrelayjava.protocol;

/**
 * 节点间交互的消息基类型，全部交互通过本地回环网络模拟。
 */
public sealed interface Message
        permits AnnounceMessage, TxRequestMessage, TxResponseMessage, BlockRequestMessage, BlockResponseMessage {
    String senderId();

    String recipientId();
}
