# 内容级串行原语收敛改造：统一 ContentTaskGate

> 本文档针对「同一目标『同内容同时只处理一次』被拆成三套语义重叠的串行原语」这一代码问题，设计统一收敛方案。
>
> 现状三套原语：提交侧幂等键（`DebugController:76`）、内容级分析锁（`AiService:74-78`）、内容级转写锁（`AiService:227`）。
> 前两条卡死 bug（finding 1/2）的根因正是这三套锁的「跳过路径不回填」未被统一处理。
>
> 最后更新 2026-08-30，尚未实施。

---

## 一、背景与问题

### 1.1 三套原语现状

「同一内容同一时刻只处理一次」这一**同一个目标**，当前由三套独立原语 + 一个状态校验共同实现，各自由不同阶段为修不同 bug 加入：

| 原语 | Key / 类型 | 位置 | 抢不到时的行为 | 生命周期 |
|------|-----------|------|---------------|----------|
| 提交侧幂等键 | `analysis:active:{contentHash}`，`setIfAbsent` 30s TTL 字符串 | `DebugController:76` | **立即跳过**，返回「任务提交中」 | 成功靠 TTL 自然过期；失败在 catch 显式 delete |
| 内容级分析锁 | `lock:analysis:{contentHash}`，Redisson `RLock.tryLock(600s)` | `AiService:74-78` | **阻塞等待 600s** 复用，超时才让位 | `finally` 内 `isHeldByCurrentThread` 后 unlock |
| 内容级转写锁 | `lock:analysis-context:{contentHash}`，Redisson `RLock.tryLock(600s)` | `AiService:227` | 先查归属复用，复用不到返回 `null` | `finally` 内 `locked && isHeldByCurrentThread` unlock |

叠加的**第四层**：`DebugController:69` 的 `aiStatus`（`PENDING`/`PROCESSING`）状态校验，与幂等键在「防重复提交」上进一步重叠。

除上述「并发互斥」原语外，同一目标还有一条 **DB 兜底复用路线**（历史维度，防「重复处理过」），与前三套互斥原语分属两个不同维度：

| 机制 | 载体 | 位置 | 作用 |
|------|------|------|------|
| Redis 归属缓存 | `analysis:completed-owner:{contentHash}` / `analysis:context-owner:{contentHash}`（7 天 TTL） | `AiService` `rememberAnalysisResult` / `rememberTranscriptOwner` | 已处理内容的快速复用缓存 |
| **DB 反查兜底** | `file_md5` 反查 `selectCompletedAnalysisByMd5` / `selectCompletedTranscriptByMd5` | `MediaFileMapper:21/29` | 归属缓存失效/过期后的持久化复用，权威数据源是 MySQL |

即「同一内容只处理一次」实际被拆成**两个维度、六套机制**：并发互斥（幂等键 / 分析锁 / 转写锁 / `aiStatus` 校验）防「同时」，结果复用（Redis 缓存 / DB 反查）防「重复处理过」。收敛重点是并发互斥侧，但结果复用线同样散落在 `AiService`，需一并纳入 `ContentTaskGate`。

### 1.2 语义差异点

三者虽都叫「内容级串行」，但关键语义各自为政：

1. **Key 形态不同**：TTL 字符串（幂等键）vs 看门狗 RLock（两把锁）。
2. **等待语义不同**：立即跳过（幂等键）vs 阻塞等待复用（分析锁）vs 先查后跳（转写锁）。
3. **释放/回滚语义不同**：delete（幂等键失败）/ TTL 自然过期（幂等键成功）/ finally unlock（两把锁）。
4. **跳过善后不同**：返回成功让前端轮询（幂等键）/ 交补偿兜底（分析锁超时）/ 返回 null 由调用方回滚（转写锁）。

### 1.3 成因

- **逐 bug 打补丁，未沉淀抽象**：幂等键为修「并发重复投 MQ」、分析锁为修「同内容并发重复分析」、转写锁为修「同内容重复 ASR」，每次都只堵当下症状。
- **Key 集中、语义分散**：`AnalysisTaskKeys` 只统一了 key 字符串，真正决定对错的「获取/等待/释放/跳过善后」散在 `DebugController` 与 `AiService` 两个类。
- **跳过路径不回填**：三套原语「抢不到就跳过」的善后动作不一致，任何一条「跳过但没回填/回滚」的路径被遗漏，就会复现 finding 1/2 的卡死。

### 1.4 影响

1. **语义漂移**：分析锁已从「无参看门狗」演化为「600s 阻塞等待」，幂等键仍是 30s TTL，两者时间窗与含义脱节。
2. **互相不可见**：新增入口（再加一个触发分析/提取的接口）会再复制一套，不一致持续累积。
3. **难以审计**：无法用单一不变量回答「是否存在双处理窗口 / 死锁 / 永久卡 PENDING」。
4. **空窗**：幂等键 30s 过期后、分析锁尚未加锁前，重复提交只能靠 `aiStatus` 兜底（第四层重叠）。

---

## 二、目标与不变量

### 2.1 目标

- 把三套「内容级串行」收敛为一个 `ContentTaskGate` 抽象，统一「获取 / 等待 / 释放 / 跳过善后」语义。
- 固化一条强制不变量：**「跳过 ⇒ 复用或回滚」——任何非持锁路径都必须显式复用他人结果或回滚到可重试态，禁止静默返回成功。**

### 2.2 关键边界（避免误解）

- **不是合并锁的数量**：分析锁（覆盖「转写+总结」）与转写锁（只覆盖 ASR）**粒度不同**，且 `lock:analysis → lock:analysis-context` 的嵌套顺序是防死锁的关键，**必须保留两把锁**。
- **收敛的是语义**：把两把锁的「获取/等待/释放/跳过」与幂等键的「抢占/回滚」统一到 `ContentTaskGate`，用同一种返回契约（`GateOutcome`）对接。

---

## 三、方案设计

### 3.1 统一返回契约 `GateOutcome`

新增枚举（放 `common` 包），作为 gate 与业务层的唯一对接类型：

```java
public enum GateOutcome {
    /** 我持有锁并已执行完毕 */
    PROCEED,
    /** 他人已完成，结果已在锁内回填（复用路径善后完毕） */
    REUSE,
    /** 他人进行中或锁超时，本次让位（调用方必须交补偿/回滚，禁止静默成功） */
    DEFER
}
```

约定：`DEFER` 是**必须显式善后**的信号——调用方要么把记录交给补偿调度器、要么回滚到可重试态，绝不允许直接 `return Result.ok(...)`。

### 3.2 新增 `ContentTaskGate` 服务

核心职责：**锁的获取 / 等待 / 释放 / 超时判定**，业务通过回调在锁内做「复用 or 执行」。

```java
package com.example.server.service;

/**
 * 内容级任务门：统一「内容级互斥 + 跳过善后」的入口。
 *
 * 不变量：任何非 PROCEED 的返回（REUSE/DEFER），调用方必须复用或回滚，
 * 禁止静默返回成功 —— 这是 finding 1/2 卡死 bug 的教训固化点。
 */
@Service
public class ContentTaskGate {

    /** 分析锁等待时长（秒） */
    private final long analysisLockWaitSeconds;
    /** 转写锁等待时长（秒） */
    private final long contextLockWaitSeconds;
    /** 提交侧活跃标记 TTL */
    private final Duration submitActiveTtl;

    // 依赖：RedissonClient、StringRedisTemplate

    /**
     * 在内容级分析锁内执行 action。
     * 抢锁超时/被中断 → 返回 DEFER；否则锁内执行 action 并透传其 GateOutcome。
     */
    public GateOutcome inAnalysisLock(String contentHash, Supplier<GateOutcome> action) {
        RLock lock = redissonClient.getLock(AnalysisTaskKeys.analysisLock(contentHash));
        boolean locked;
        try {
            locked = lock.tryLock(analysisLockWaitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GateOutcome.DEFER; // 中断让位，交补偿
        }
        if (!locked) {
            return GateOutcome.DEFER; // 超时让位，交补偿
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    /** 在内容级转写锁内执行 action，语义同 inAnalysisLock。 */
    public GateOutcome inTranscribeLock(String contentHash, Supplier<GateOutcome> action) { /* 同上 */ }

    /** 提交侧：原子抢占「提交中」标记，抢不到说明并发提交中。 */
    public boolean tryMarkSubmitting(String contentHash) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue()
                .setIfAbsent(AnalysisTaskKeys.active(contentHash), "1", submitActiveTtl));
    }

    /** 提交侧：回滚提交标记（发 MQ 失败等）。 */
    public void rollbackSubmitting(String contentHash) {
        redisTemplate.delete(AnalysisTaskKeys.active(contentHash));
    }
}
```

**结果复用线也纳入 gate**：`ContentTaskGate` 同时收敛「归属复用」的 Redis 缓存读写 + DB 反查兜底，暴露四个方法，消除 `AiService` 里散落的 `resolve*` / `remember*` 私有方法：

```java
    /** 复用查询：Redis 归属缓存 → 失效则回退 DB 按 file_md5 反查 → 命中则回填并返回 owner。 */
    public MediaFile resolveAnalysis(String contentHash, MediaFile target);   // 内部走 selectCompletedAnalysisByMd5
    public MediaFile resolveTranscript(String contentHash, MediaFile target); // 内部走 selectCompletedTranscriptByMd5
    /** 归属登记：结果/转写落库后写 Redis 缓存（7 天 TTL）。 */
    public void rememberAnalysis(String contentHash, Long mediaId);
    public void rememberTranscript(String contentHash, Long mediaId);
```

> 权衡：`ContentTaskGate` 因此依赖 `MediaFileMapper`（归属的权威数据源是 MySQL），不再是纯 Redis 门；收益是「复用线」的 Redis 缓存 + DB 反查不再散落 `AiService`。

**锁嵌套顺序固化进 gate**：`asyncAnalyze` 外层走 `inAnalysisLock`，其内部再走 `inTranscribeLock`（转写阶段），顺序恒为 `lock:analysis → lock:analysis-context`，与现有死锁规避一致，且不再由业务手写两把锁。

### 3.3 收敛提交侧幂等键

`DebugController.aiAnalyze` 中：

- `setIfAbsent(activeKey, id, 30s)` → `gate.tryMarkSubmitting(contentHash)`；
- catch 回滚里的 `redisTemplate.delete(activeKey)` → `gate.rollbackSubmitting(contentHash)`；
- 「抢不到 → `Result.ok("任务提交中")`」保留，但语义明确为 `DEFER`：这是**短窗口快路径**，真正的「任务是否在跑」由消费侧的 `aiStatus` + 分析锁权威判定，前端仍靠 3s 轮询拿结果。

> 定位说明：幂等键的 30s TTL 只覆盖「aiStatus 校验通过后 → 发 MQ」的并发窗口，是最短窗口的提交防重；它不是执行防重的权威来源。权威来源是 `aiStatus` 状态机（落库）+ 分析锁（执行期）。

### 3.4 收敛分析锁

`AiService.asyncAnalyze` 中，把「`tryLock(600s)` + `finally unlock`」整体替换为：

```java
GateOutcome outcome = contentTaskGate.inAnalysisLock(contentHash, () -> {
    // 锁内：查归属复用（resolveAnalysisResult，复用则回填并返回 REUSE）
    if (resolveAnalysisResult(mediaFile, contentHash)) {
        evictCache(mediaFile);
        return GateOutcome.REUSE;
    }
    // 未复用：真正执行（转写 + 总结），内部再走 inTranscribeLock
    return doAnalyze(mediaFile, contentHash); // 成功返回 PROCEED
});
if (outcome == GateOutcome.DEFER) {
    // 让位：刷新 ai_process_at 交补偿，不静默返回成功
    log.info("等待分析锁超时，让位交补偿, mediaId={} contentHash={}", mediaId, contentHash);
    return;
}
```

**关键改动**：`resolveAnalysisResult` / `resolveTranscript` 现有逻辑（查 Redis 归属 → 回退 DB 按 `file_md5` 反查 → 回填 + 登记归属）**从 `AiService` 收敛进 `ContentTaskGate` 的 `resolveAnalysis`/`resolveTranscript`/`rememberAnalysis`/`rememberTranscript`**（gate 依赖 `MediaFileMapper`），统一在锁内由回调调用，返回 `REUSE` 时回填已完成；`DEFER` 时 `asyncAnalyze` 只 `return`，把状态保持为 `PENDING/PROCESSING` 交给补偿调度器——这是「跳过不回填」的正规化路径。

### 3.5 收敛转写锁

`transcribeWithReuse` 改为：

```java
private GateOutcome transcribeOrReuse(MediaFile mediaFile, String contentHash) {
    return contentTaskGate.inTranscribeLock(contentHash, () -> {
        String reusable = resolveTranscript(mediaFile, contentHash); // 复用则内部回填
        if (reusable != null) return GateOutcome.REUSE;
        String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
        // 落库 + 登记归属，返回 PROCEED
        ...
        return GateOutcome.PROCEED;
    });
}
```

调用方 `asyncTranscribe` / `asyncAnalyze` 对 `DEFER` 的善后：`asyncTranscribe` 回滚 `transcriptStatus` 到 `NONE`（现有逻辑保留），`asyncAnalyze` 把 `DEFER` 视作「转写锁等待超时」落瞬时失败 `AiAnalysisException(retryable=true, LOCK)` 交补偿——二者都显式回滚，不再有「返回 null 后无人善后」的路径。

### 3.6 状态流转（收敛后）

```
提交侧：  aiStatus 校验 → tryMarkSubmitting(短窗口) → 限流 → 置 PENDING → 发 MQ
                      │抢不到(DEFER) → 返回成功让前端轮询
                      │失败 → rollbackSubmitting + 回滚 aiStatus

执行侧：  inAnalysisLock(长窗口)
            ├─ REUSE → 回填结果，结束
            ├─ PROCEED → 转写(inTranscribeLock) → 总结 → 登记归属 → SUCCESS
            └─ DEFER  → 让位，保持 PENDING/PROCESSING 交补偿

转写侧：  inTranscribeLock
            ├─ REUSE → 回填转写，返回
            ├─ PROCEED → ASR → 登记归属
            └─ DEFER  → 回滚(异步提取→NONE / 分析→瞬时失败交补偿)
```

---

## 四、具体改造点（文件级）

### 新增文件

```
common/GateOutcome.java                  # 三态枚举
service/ContentTaskGate.java             # 统一锁语义 + 提交标记 + 归属复用(Redis 缓存 + DB 反查)
```

### 修改文件

```
utils/AnalysisTaskKeys.java              # 补注释明确 active(短窗口) vs lock:*(长窗口) 定位
controller/DebugController.java          # 幂等键调用替换为 gate.tryMarkSubmitting/rollbackSubmitting
service/AiService.java                   # 两把锁收敛为 gate.inAnalysisLock/inTranscribeLock
service/AnalysisCompensationScheduler.java  # 不改逻辑，仅确认 DEFER 路径交其兜底（可加注释）
resources/application.properties         # 新增 ai.submit-active-ttl-seconds=30（幂等键 TTL 配置化）
```

> `VideoAnalysisConsumer` / `VideoAnalysisDlqConsumer` / `RateLimitService` / `MediaService` 不改。

---

## 五、迁移步骤（分阶段、向后兼容）

### Phase 1：落地抽象（不改行为）

1. 新增 `GateOutcome` 与 `ContentTaskGate`，内部实现与现有三处「逐字等价」的锁/标记逻辑。
2. 单元测试覆盖 `inAnalysisLock`/`inTranscribeLock` 的 `PROCEED/REUSE/DEFER` 三分支、`tryMarkSubmitting/rollbackSubmitting`。

### Phase 2：逐处替换（保持行为一致）

3. `DebugController` 幂等键 → `gate.tryMarkSubmitting` / `rollbackSubmitting`。
4. `AiService.asyncAnalyze` 分析锁 → `gate.inAnalysisLock`，`DEFER` 分支显式 `return`（不写状态，交补偿）。
5. `AiService.transcribeWithReuse` → `gate.inTranscribeLock`，返回 `GateOutcome`，`DEFER` 由调用方回滚。
6. `AiService` 的 `resolveAnalysisResult` / `resolveTranscript` / `rememberAnalysisResult` / `rememberTranscriptOwner` 收敛进 `gate.resolveAnalysis` / `resolveTranscript` / `rememberAnalysis` / `rememberTranscript`（含 DB 反查兜底 `selectCompleted*ByMd5`）。

每替换一处即用 `compile-server` 编译 + 走 `analyze-video` 链路回归，避免一次性大改难定位。

### Phase 3：固化不变量

6. `ContentTaskGate` 类 javadoc 写入不变量；`AiService` 内对 `GateOutcome` 用 `switch` 穷举（缺 `DEFER` 分支编译告警），从结构上防止未来新增入口漏掉「跳过善后」。

---

## 六、文件变更清单

```
新增  common/GateOutcome.java
新增  service/ContentTaskGate.java
修改  utils/AnalysisTaskKeys.java
修改  controller/DebugController.java
修改  service/AiService.java
修改  resources/application.properties
（可选）service/AnalysisCompensationScheduler.java  # 仅注释
```

---

## 七、验证方式

1. **编译**：`compile-server` 通过。
2. **同内容并发提交**：两个请求同时 `GET /debug/ai?id=A&id=B`（同 `file_md5`），确认只投递一次 MQ、幂等键 30s 内抢不到时返回成功且前端轮询出唯一结果。
3. **换 mediaId 重复上传**：同内容上传两次，确认第二个走 `REUSE` 复用，无重复 ASR/总结。
4. **锁超时让位**：临时把 `ai.analysis-lock-wait-seconds` 调小到 1s 制造锁竞争，确认 `DEFER` 后记录不卡 `PENDING`，补偿调度器 20 分钟后重新触发并最终 `SUCCESS/FAILED`。
5. **回归 finding 1/2**：复现原卡死场景，确认「跳过 ⇒ 复用或回滚」不变量生效，无静默成功。
6. **转写失败回滚**：异步提取抢转写锁超时，确认 `transcriptStatus` 回滚 `NONE`（不卡 `PROCESSING`）。

---

## 八、风险与回滚

| 风险 | 缓解 |
|------|------|
| 抽象引入后「复用/登记归属」逻辑迁移出错 | Phase 1 逐字等价实现 + 单测锁定三分支，Phase 2 逐处替换并即时回归 |
| 锁等待语义改动（如 TTL 与 wait 错配） | wait 参数沿用现有 `ai.*-lock-wait-seconds`，不引入新默认值 |
| `DEFER` 分支误写成静默成功 | `switch` 穷举 + 代码评审重点检查 |
| 补偿调度器与 `DEFER` 协作不清 | Phase 2 后跑一次锁竞争场景验证补偿能兜底 |

回滚：Phase 1/2 均为「等价重构 + 逐处替换」，任何一处行为偏离可单点 revert 到该文件改动前版本，不影响其余。

---

## 九、遗留事项

| 事项 | 说明 |
|------|------|
| 幂等键是否可彻底去除 | 若后续给 `置 PENDING` 加乐观锁（`ai_status=NONE` 条件更新），可去掉 `analysis:active` 短窗口键，进一步收敛为「单锁 + 状态机」 |
| 复用线收敛的边界 | gate 依赖 `MediaFileMapper` 后不再是纯 Redis 门；若想解耦可再拆一层 `AnalysisOwnerRepository` 隔离 DB 反查 |
| 补偿调度器与 gate 的显式契约 | 当前靠 `DEFER` 后「不写状态」隐式交补偿，可考虑抽 `DeferAction` 枚举显式表达「交补偿/回滚/落失败」 |
| 全链路 traceId | 与 gate 无关，但收敛后日志关联更集中，可顺手加 `contentHash` 到日志上下文 |
| `FfmpegUtils` 抛异常化 | 既有遗留项，本次不动 |
