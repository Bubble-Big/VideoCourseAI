# 内容级串行原语收敛改造：统一 ContentTaskGate

> 针对「同一目标『同内容同时只处理一次』被拆成多套语义重叠的串行原语」这一代码问题，设计统一收敛方案。
> 前两条卡死 bug（finding 1/2）的根因正是这些锁的「跳过路径不回填」未被统一处理。
>
> 最后更新 2026-08-30，尚未实施。

---

## 一、问题

「同内容只处理一次」这一个目标，当前被拆成**两个维度、六套机制**，各自由不同阶段为修不同 bug 加入，语义各自为政：

| 维度 | 机制 | 载体 / 类型 | 位置 | 抢不到 / 失效时行为 |
|------|------|------------|------|-------------------|
| **并发互斥**<br>（防「同时」） | 提交侧幂等键 | `analysis:active:{hash}`，`setIfAbsent` 30s TTL | `DebugController:76` | 立即跳过 |
| | 内容级分析锁 | `lock:analysis:{hash}`，`RLock.tryLock(600s)` | `AiService:74` | 阻塞等待复用 |
| | 内容级转写锁 | `lock:analysis-context:{hash}`，`RLock.tryLock(600s)` | `AiService:227` | 先查归属复用 |
| | aiStatus 状态校验 | `ai_status` DB 字段 | `DebugController:69` | 幂等返回 |
| **结果复用**<br>（防「重复处理过」） | Redis 归属缓存 | `analysis:*-owner:{hash}`，7 天 TTL | `AiService` | 快速复用 |
| | DB 反查兜底 | `file_md5` 反查 `selectCompleted*ByMd5` | `MediaFileMapper:21/29` | 持久复用（权威） |

**根因**：逐 bug 打补丁未沉淀抽象；`AnalysisTaskKeys` 只统一了 key 字符串，真正决定对错的「获取 / 等待 / 释放 / 跳过善后」散在 `DebugController` 与 `AiService`。

**影响**：锁语义随需求漂移、互相不可见；新增入口即复制一套；无法用单一不变量回答「是否存在双处理窗口 / 死锁 / 永久卡 PENDING」。

---

## 二、目标与边界

**目标**：把上述机制收敛为一个 `ContentTaskGate` 抽象，统一「获取 / 等待 / 释放 / 跳过善后」语义，并固化强制不变量：

> **「跳过 ⇒ 复用或回滚」——任何非持锁路径都必须显式复用他人结果或回滚到可重试态，禁止静默返回成功。**

**边界（避免误解）**：

- **不合并锁的数量**：分析锁（覆盖「转写+总结」）与转写锁（只覆盖 ASR）粒度不同，`lock:analysis → lock:analysis-context` 嵌套顺序是防死锁关键，**两把锁保留**。
- **收敛的是语义**：把锁的「获取/等待/释放/跳过」与幂等键的「抢占/回滚」统一到 `ContentTaskGate`，用同一种返回契约对接。

---

## 三、方案设计

### 3.1 统一契约与核心抽象

新增 `GateOutcome` 枚举（`common` 包），作为 gate 与业务层唯一对接类型：

- `PROCEED`：我持有锁并已执行完毕
- `REUSE`：他人已完成，结果已在锁内回填
- `DEFER`：他人进行中或锁超时，本次让位（**调用方必须交补偿/回滚，禁止静默成功**）

新增 `ContentTaskGate` 服务，收敛两类职责：

| 方法 | 职责 |
|------|------|
| `inAnalysisLock(hash, action)` | 在分析锁内执行回调；抢锁超时/中断 → `DEFER` |
| `inTranscribeLock(hash, action)` | 在转写锁内执行回调；语义同上 |
| `resolveAnalysis` / `resolveTranscript` | 复用查询：Redis 归属 → 失效则回退 DB 按 `file_md5` 反查 → 命中回填 |
| `rememberAnalysis` / `rememberTranscript` | 归属登记：落库成功后写 Redis 缓存（7 天 TTL） |
| `tryMarkSubmitting` / `rollbackSubmitting` | 提交侧短窗口幂等标记（替代 `setIfAbsent`/`delete`） |

依赖：`RedissonClient`、`StringRedisTemplate`、`MediaFileMapper`（复用线 DB 反查的权威数据源是 MySQL）。

### 3.2 三个收敛点

| 收敛点 | 现状 | 改后 |
|--------|------|------|
| 提交侧幂等键 | `DebugController` 手写 `setIfAbsent` + catch `delete` | `gate.tryMarkSubmitting` / `rollbackSubmitting`；「抢不到→返回成功」保留（前端轮询） |
| 内容级分析锁 | `AiService.asyncAnalyze` 手写 `tryLock(600s)` + `finally unlock` | `gate.inAnalysisLock`，锁内回调做「查归属复用（REUSE）或真正执行（PROCEED）」 |
| 内容级转写锁 | `AiService.transcribeWithReuse` 手写 `tryLock` + 返回 `null` | `gate.inTranscribeLock`，`DEFER` 由调用方显式回滚 |

**锁嵌套顺序固化进 gate**：`asyncAnalyze` 外层 `inAnalysisLock`，转写阶段内层 `inTranscribeLock`，顺序恒为 `lock:analysis → lock:analysis-context`，不再由业务手写两把锁。

### 3.3 状态流转（收敛后）

```
提交侧：  aiStatus 校验 → tryMarkSubmitting(短窗口) → 限流 → 置 PENDING → 发 MQ
                      │抢不到 → 返回成功让前端轮询
                      │失败 → rollbackSubmitting + 回滚 aiStatus

执行侧：  inAnalysisLock
            ├─ REUSE → 回填结果
            ├─ PROCEED → 转写(inTranscribeLock) → 总结 → 登记归属 → SUCCESS
            └─ DEFER  → 让位，保持 PENDING/PROCESSING 交补偿

转写侧：  inTranscribeLock
            ├─ REUSE → 回填转写
            ├─ PROCEED → ASR → 登记归属
            └─ DEFER  → 回滚(异步提取→NONE / 分析→瞬时失败交补偿)
```

---

## 四、文件变更与迁移

### 4.1 文件清单

| 类型 | 文件 | 改动 |
|------|------|------|
| 新增 | `common/GateOutcome.java` | 三态枚举 |
| 新增 | `service/ContentTaskGate.java` | 锁语义 + 提交标记 + 归属复用 |
| 修改 | `controller/DebugController.java` | 幂等键替换为 `gate.tryMarkSubmitting`/`rollbackSubmitting` |
| 修改 | `service/AiService.java` | 两把锁 + `resolve*`/`remember*` 迁入 gate |
| 修改 | `utils/AnalysisTaskKeys.java` | 补注释明确 `active`(短窗口) vs `lock:*`(长窗口) |
| 修改 | `resources/application.properties` | 新增 `ai.submit-active-ttl-seconds=30`、`content.gate.enabled=true` |
| 不改 | `VideoAnalysisConsumer` / `VideoAnalysisDlqConsumer` / `RateLimitService` / `MediaService` / `AnalysisCompensationScheduler` | — |

### 4.2 迁移步骤（向后兼容）

1. **Phase 1 落地抽象**：新增 `GateOutcome` 与 `ContentTaskGate`，内部逻辑与现有三处「逐字等价」；单测覆盖 `PROCEED/REUSE/DEFER` 三分支与 `tryMarkSubmitting`/`rollbackSubmitting`。
2. **Phase 2 逐处替换**：依次替换幂等键 → 分析锁 → 转写锁 → `resolve*`/`remember*`，每处替换即 `compile-server` 编译 + `analyze-video` 链路回归。
3. **Phase 3 固化**：gate javadoc 写入不变量；`GateOutcome` 用 `switch` 穷举（缺 `DEFER` 编译告警）；落地 `content.gate.enabled` 特性开关。

---

## 五、风险与防范

> 基于方案细节推导的真实风险，**R1 对生产影响最大**，实施前必须完成对应防范项。

| 等级 | 风险 | 成因 | 防范（实施前必做） |
|------|------|------|--------------------|
| **R1（P0）** | 锁内查库的性能放大效应 | `resolve*` 在持有 `RLock` 期间执行 `selectCompleted*ByMd5`，索引缺失或慢 SQL 会长时间占用锁，高并发下拖垮 Redis 连接池与 Tomcat 线程 | ① `EXPLAIN` 两个反查 SQL 确认走 `file_md5` 索引（建议建 `(file_md5, ai_status, id)` 复合索引）；② 慢查询监控；③ 确认 DB 反查仅在 Redis 归属 miss 时触发（降频） |
| R2（P1） | DEFER 风暴加重补偿调度器 | 锁竞争高峰产生大量 `DEFER`，20 分钟后落入补偿扫表 | 现状非即时风暴（阈值 20min、`SCAN_LIMIT=100`）；缓解：`DEFER` 时刷新 `ai_process_at` 延后，或改用 RocketMQ 延迟消息（见遗留） |
| R3（P1） | 缓存/DB 最终一致性空窗 | `remember*` 与落库解耦后误用「先写缓存」或落库回滚 → 脏归属 | 硬性写死「先落 DB（SUCCESS 提交）再写缓存」+ 缓存 TTL 7 天自愈 |
| R4（P2） | 缺特性开关，回滚粒度粗 | 无 Feature Flag，P0 故障只能整体回滚镜像 | `content.gate.enabled` 开关，`false` 走旧三套原语 |

**回滚**：`content.gate.enabled=false` 秒级切回旧逻辑；Phase 1/2 均为等价重构，单点 revert 即可。

---

## 六、验证

1. **编译**：`compile-server` 通过。
2. **同内容并发提交**：同 `file_md5` 两个请求，确认只投递一次 MQ，前端轮询出唯一结果。
3. **换 mediaId 重复上传**：第二个走 `REUSE` 复用，无重复 ASR/总结。
4. **锁超时让位**：调小 `ai.analysis-lock-wait-seconds` 制造锁竞争，确认 `DEFER` 后不卡 `PENDING`，补偿兜底最终 `SUCCESS/FAILED`。
5. **回归 finding 1/2**：复现原卡死场景，确认「跳过 ⇒ 复用或回滚」生效，无静默成功。
6. **转写失败回滚**：异步提取抢转写锁超时，确认 `transcriptStatus` 回滚 `NONE`。

---

## 七、遗留事项

| 事项 | 说明 |
|------|------|
| 幂等键是否可彻底去除 | 若给「置 PENDING」加乐观锁（`ai_status=NONE` 条件更新），可去掉 `analysis:active`，收敛为「单锁 + 状态机」 |
| 复用线解耦 | gate 依赖 `MediaFileMapper` 后不再是纯 Redis 门；可再拆 `AnalysisOwnerRepository` 隔离 DB 反查 |
| 补偿显式契约 | 当前靠 `DEFER` 后「不写状态」隐式交补偿；可抽 `DeferAction` 枚举显式表达「交补偿/回滚/落失败」 |
| 全链路 traceId | 收敛后顺手加 `contentHash` 到日志上下文 |
| `FfmpegUtils` 抛异常化 | 既有遗留项，本次不动 |
