# 紧凑区块高效传播（Compact Block Relay）

本模块在不依赖任何外部组件或真实网络的前提下，用同一进程内的**本地回环连接**模拟区块网络节点，
实现了类似 BIP-152 的紧凑区块传播协议：新区块通知只携带区块头与每笔交易的**短标识**，
接收方优先用本地交易池重建区块，只对缺失交易发起一次合并补齐请求，必要时回退到完整区块传输。

## 一、组件结构

- `relay/model`：`Transaction`、`BlockHeader`（含每笔交易的完整标识列表）、`Block`。
- `relay/protocol`：协议消息
  - `CompactBlockAnnouncement`：区块头 + 短标识密钥 `nonce` + 每笔交易短标识。
  - `TxnRequest` / `TxnResponse`：缺失交易的批量补齐请求与响应。
  - `FullBlockRequest` / `FullBlockResponse`：完整区块回退请求与响应。
- `relay/network/LoopbackNetwork`：进程内回环网络，单线程顺序投递，支持固定延迟、
  随机丢包与按消息类型过滤（用于测试超时/失败/乱序）。
- `relay/mempool/Mempool`：有上界的本地交易池，超限按 LRU 淘汰。
- `relay/util/ShortTxIdCalculator`：短标识算法；`BoundedLruCache`：有上界去重缓存。
- `relay/store/FileBlockStateStore`：追加日志持久化，区分“已确认区块”与“未完成重建”。
- `relay/node/Node`：节点状态机，串联通知、重建、补齐、校验、回退、上链与恢复。

## 二、短标识与碰撞处理

- 短标识 = `SHA-256(完整交易标识 || 区块 nonce)` 的前 N 个字节，默认 **6 字节（48 bit）**。
- 48 bit 在单区块约 3000 笔交易时区块内自碰撞概率约 1e-6，在带宽与碰撞概率之间折中。
- 重建按短标识从本地交易池预填：
  - 短标识命中多笔交易（歧义/碰撞）时，该槽位按“未命中”处理，走完整标识补齐。
  - 即使短标识把某槽位**误指向错误交易**，重建完成前也会逐笔比对
    “区块头声明的完整标识”与“实际重建交易的完整标识”。
  - **任何不一致都立即回退到完整区块传输**，接收方永远不会接受交易内容错误的区块。

## 三、协议交互流程

正常路径：

1. 出块节点本地应用区块，向所有对端发送 `CompactBlockAnnouncement`（区块头 + nonce + 短标识）。
2. 接收方对未处理、未过期的通知建立重建会话，并先持久化一条 `P(pending)` 记录。
3. 接收方用本地交易池按短标识重建，缺失的交易**合并成一次** `TxnRequest`（按完整标识）。
4. 出块节点用 `TxnResponse` 返回交易；接收方加入交易池后逐笔校验完整标识。
5. 全部一致后原子地应用区块：持久化 `C(confirmed)`、清理会话与 pending、从交易池移除已上链交易。

回退路径（任一条件触发即汇聚到同一个幂等入口）：

- 重建总耗时超过 `rebuildTimeoutMillis`。
- 补齐请求超时（`requestTimeoutMillis`）或请求失败（丢包/对端无响应）。
- 逐笔完整标识校验不一致（短标识碰撞导致误配）。

回退时发送 `FullBlockRequest`，收到 `FullBlockResponse` 后做结构性校验再上链；
超过 `fullBlockRetry` 仍失败则安全丢弃会话并清理 pending。

去重与迟到处理：

- 已确认区块、进行中的同区块会话、高度不高于链尖的过期通知，一律安全丢弃。
- 重复的 `TxnResponse` / `FullBlockResponse`：区块已落地或会话已结束时直接丢弃，不重复应用。
- 父块缺失的通知进入**有界孤儿缓存**，父块上链后自动重放；缓存超限按 LRU 丢弃。
- 同一区块的多个通知并发到达时，所有状态变更都在单把锁内做判定，
  只会建立一个会话、发出一次合并补齐请求，最终恰好应用一次。
- 重建进行期间收到属于该区块的“新交易”，会立即精确补齐对应槽位并推进收敛。

## 四、崩溃恢复

持久化日志（每条记录 fsync）有三类记录：

- `P <hash> <height> <prev>`：进入未完成的紧凑重建。
- `C <base64 完整区块>`：区块已确认（含完整内容），同时终结同名 pending。
- `X <hash>`：pending 取消（重建放弃/失败）。

重启重放后，`confirmed` 集合与 `pending` 集合天然分离：

- 已确认区块可直接恢复为链尖，重复通知被丢弃，不会重复应用。
- 未完成重建**绝不**被误判为已接收；网络恢复后对端重发通知可重新重建并最终上链。

## 五、配置参数（`relay/config/RelayConfig`）

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `shortTxIdBytes` | 6 | 短标识长度（字节）。越长碰撞越低但通知越大。 |
| `rebuildTimeoutMillis` | 2000 | 紧凑重建总超时，超时即回退完整区块。 |
| `requestTimeoutMillis` | 1000 | 单次补齐/完整区块请求超时。 |
| `fullBlockRetry` | 1 | 完整区块请求允许的额外重试次数。 |
| `mempoolMaxSize` | 10000 | 本地交易池上界（LRU 淘汰）。 |
| `seenAnnouncementMaxSize` | 10000 | 通知去重记录上界（LRU）。 |
| `orphanMaxSize` | 1000 | 孤儿通知缓存上界（LRU）。 |
| `knownBlockMaxSize` | 1000 | 节点为服务对端请求而保留的完整区块上界。 |

## 六、测试

位于 `src/test/java/com/github/guoyyit/compactblockrelayjava/tests`：

- `MissingTxRecoveryTest`：缺失交易合并为一次请求补齐并上链。
- `ShortIdCollisionTest`：短标识碰撞绝不接受错误内容，回退完整区块后内容正确。
- `TimeoutFallbackTest`：补齐请求失败回退；完整区块请求超时安全丢弃，恢复后重发可上链。
- `ConcurrentAnnouncementTest`：同区块多通知并发到达，只应用一次、只发一次补齐请求。
- `InFlightNewTxTest`：重建期间新交易到达可收敛，不重复处理。
- `AnnouncementDedupTest`：已处理/过期/孤儿通知安全丢弃，去重记录与交易池均有上界。
- `OrphanReconnectTest`：孤儿子块在父块到达后自动重放上链。
- `RecoveryTest`：已确认区块与未完成重建可区分，重启后重复通知不重复应用。

运行：

```bash
mvn test
```
