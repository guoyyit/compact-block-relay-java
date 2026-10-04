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
 * @param blockRequestTimeoutMs 单次完整区块回退请求超时
 * @param maxFallbackRetries   完整区块回退请求超时后的有限重试次数，耗尽后释放进行中占位
 */
public record RelayConfig(int shortIdBytes,
                          long rebuildTimeoutMs,
                          long txRequestTimeoutMs,
                          int maxMempool,
                          int maxSeenHashes,
                          int maxOrphans,
                          int maxRequestRetries,
                          long blockRequestTimeoutMs,
                          int maxFallbackRetries) {
    /**
     * 兼容既有调用方：新增回退参数取默认值，不改变既有参数语义。
     */
    public RelayConfig(int shortIdBytes,
                       long rebuildTimeoutMs,
                       long txRequestTimeoutMs,
                       int maxMempool,
                       int maxSeenHashes,
                       int maxOrphans,
                       int maxRequestRetries) {
        this(shortIdBytes, rebuildTimeoutMs, txRequestTimeoutMs, maxMempool, maxSeenHashes,
                maxOrphans, maxRequestRetries, 1_000, 2);
    }

    public static RelayConfig defaults() {
        return new RelayConfig(8, 2_000, 500, 10_000, 4_096, 64, 1, 1_000, 2);
    }
}
