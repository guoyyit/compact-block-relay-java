package com.github.guoyyit.compactblockrelayjava.relay;

/**
 * 紧凑区块传播配置参数。
 *
 * @param shortIdBytes       短标识字节数（固定使用 64 位 SipHash，参数用于文档与断言）
 * @param rebuildTimeoutMs   紧凑重建整体超时，超时后回退完整区块
 * @param txRequestTimeoutMs 单次缺失交易补齐请求超时
 * @param maxMempool         本地交易池容量上界
 * @param maxSeenHashes      已处理/已见区块哈希去重记录上界
 * @param maxOrphans         孤儿通知缓存上界
 * @param maxRequestRetries  补齐请求失败后重试次数，耗尽后回退完整区块
 */
public record RelayConfig(int shortIdBytes,
                          long rebuildTimeoutMs,
                          long txRequestTimeoutMs,
                          int maxMempool,
                          int maxSeenHashes,
                          int maxOrphans,
                          int maxRequestRetries) {
    public static RelayConfig defaults() {
        return new RelayConfig(8, 2_000, 500, 10_000, 4_096, 64, 1);
    }
}
