# AI 分析补偿调度器加固计划书（丢失更新修复 + 重试计数绑定执行结果 + 指数退避 + 并发/异常防护）

> 状态：**待实施**（最后更新 2026-09-06）。
>
> 本计划是 [AI_ANALYSIS_COMPENSATION_PLAN.md](AI_ANALYSIS_COMPENSATION_PLAN.md) 落地后的补丁计划。补偿式重试机制本身已跑通主链路，但专项排查发现调度器在**数据正确性**、**重试计数语义**、**多实例部署**、**异常处理健壮性**、**退避算法**五个方向存在缺陷，其中两项（丢失更新、重试计数误耗尽）会导致「已成功的分析结果被覆盖」或「本该成功的任务被误判永久失败」，属于必须先修的正确性问题；退避算法问题会直接影响系统应对第三方 API 网络抖动的效率，同样列为本次必改项。

---

## 一、问题清单与严重性排序

排查依据是 `AnalysisCompensationScheduler.java`、`AiService.java`、`ContentTaskGate.java`、`MediaFileMapper.java`、`FailedAnalysisTaskService.java`、`ThreadPoolConfig.java`、`DeepSeekUtils.java`、`AliyunAsrUtils.java` 的现有实现。九项发现按根因归并为五组，严重性从高到低排列：

| 排名 | 问题 | 严重性 | 根因分类 |
|------|------|--------|----------|
| 1 | **丢失更新**：补偿调度器用查询时的旧快照覆盖原任务刚写入的成功结果 | **严重（数据正确性）** | 缺乐观锁 |
| 2 | **重试计数与真实执行结果未绑定**：触发一次「排锁等待又放弃」也会消耗一次 `ai_attempts`，可能在任务真正跑完前耗尽重试次数 | **严重（核心重试逻辑失效）** | 计数绑定「触发」而非「结果」 |
| 3 | **消费者内部重试无指数退避**：`DeepSeekUtils`/`AliyunAsrUtils` 固定 2 秒重试，持续性抖动下退避效率低 | **高（直接影响抗抖动能力）** | 退避算法过简 |
| 4 | 多实例部署下 `@Scheduled` 无分布式锁，同一卡死记录可能被并发触发多次 | 高（部署条件触发） | 无分布式协调 |
| 5 | `ai_attempts` 被「用户手动重试」清零与「补偿自动重试」自增两条路径共用，语义互相打架 | 中 | 字段复用冲突 |
| 6 | `compensateOne()` 循环无单条记录异常隔离，一条记录处理异常可能中断整轮扫描 | 中 | 缺防御性代码 |
| 7 | 线程池 `RejectedExecutionException` 在补偿触发时被静默吞掉，`attempts` 已加但任务未真正提交 | 中 | 异常边界缺失 |
| 8 | `selectStalledAnalysis` 缺专用复合索引，大数据量下有全表扫描风险 | 低（性能，非正确性） | 缺索引 |

问题「进程重启丢队列的 `@Async` 内存态」与「`ai_process_at` 刷新时机滞后于锁等待」在排查中确认是问题 2 的表现形式而非独立缺陷，**修复问题 2 时一并覆盖，不单独立项**。

---

## 二、问题 1：丢失更新（Lost Update）

### 2.1 现状代码

`AnalysisCompensationScheduler.java`：

```java
@Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
public void compensate() {
    LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofMinutes(thresholdMinutes));
    List<MediaFile> stalled = mediaFileMapper.selectStalledAnalysis(threshold, SCAN_LIMIT);   // ← 查询快照 f
    for (MediaFile f : stalled) {
        compensateOne(f);
    }
}

private void compensateOne(MediaFile f) {
    int attempts = (f.getAiAttempts() == null ? 0 : f.getAiAttempts()) + 1;
    f.setAiAttempts(attempts);
    f.setAiProcessAt(LocalDateTime.now());
    if (attempts >= maxAttempts) {
        f.setAiStatus(AiStatus.FAILED.name());
        f.setAiSummary(null);
        mediaFileMapper.updateById(f);      // ← 用查询时的旧快照 f 全字段覆盖写回
        failedTaskService.record(f.getId(), new AiAnalysisException("重试耗尽，判定失败", false), f.getAiAttempts());
        return;
    }
    mediaFileMapper.updateById(f);          // ← 同样的问题：全字段覆盖
    aiService.asyncAnalyze(f.getId());
}
```

`AiService.asyncAnalyze()` 成功路径（第 98-106 行）：

```java
String summary = aiAnalysisStrategy.generateSummaryFromText(text);
mediaFile.setAiSummary(summary);
mediaFile.setAiStatus(AiStatus.SUCCESS.name());
mediaFileMapper.updateById(mediaFile);   // ← 原任务真正写入成功结果
```

### 2.2 竞态时序

```
T0   补偿调度器 selectStalledAnalysis 查到记录 f（快照：PROCESSING / summary=null）
T0+ε 原任务（此前被判定"卡死"但实际仍在执行）此刻真正跑完，
      写入 SUCCESS + summary 内容
T0+δ 补偿调度器执行 mediaFileMapper.updateById(f)
      —— f 手里仍是 T0 时刻的旧快照（PROCESSING / summary=null）
      —— MyBatis-Plus 默认全字段更新，且 MediaFile 无 @Version 字段
      —— 原任务刚写入的 SUCCESS 结果被整个覆盖回 PROCESSING/null
      —— 若此时 attempts 恰好到达 maxAttempts，还会连带误判为 FAILED
```

**根因**：`MediaFile`（`entity/MediaFile.java`）没有版本号字段，`updateById` 是无条件的「查询快照 → 全量写回」，查询和写回之间的窗口没有任何机制能感知「数据已被别人改过」。

**触发条件**：不需要多实例部署，单实例下只要补偿调度器的查询快照时间点与原任务的真正完成时间点足够接近即可触发，触发门槛低于问题 4（多实例并发）。

### 2.3 修复方案：乐观锁版本号 + 条件更新

**方案选择**：给 `MediaFile` 增加 MyBatis-Plus 标准的 `@Version` 字段，而不是手写条件 SQL，理由是版本号可以被后续所有写路径（不只是补偿调度器）自动复用，一次改造覆盖全部潜在的并发写入点。

#### 2.3.1 数据库迁移

新增 `db/V6__add_media_file_version.sql`：

```sql
ALTER TABLE media_files
  ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（防补偿调度器与原任务写入竞态覆盖）';
```

#### 2.3.2 `entity/MediaFile.java`

```java
import com.baomidou.mybatisplus.annotation.Version;

// ……现有字段……

@Version
private Integer version;   // 乐观锁版本号，MyBatis-Plus updateById 自动附加 WHERE version=? 并 +1
```

#### 2.3.3 `AnalysisCompensationScheduler.compensateOne()`

`updateById` 加了 `@Version` 后行为自动变为条件更新（`UPDATE ... SET version=version+1 WHERE id=? AND version=?`），返回值是受影响行数。需要显式判断这个返回值：

```java
private void compensateOne(MediaFile f) {
    int attempts = (f.getAiAttempts() == null ? 0 : f.getAiAttempts()) + 1;
    f.setAiAttempts(attempts);
    f.setAiProcessAt(LocalDateTime.now());
    boolean exhausted = attempts >= maxAttempts;
    if (exhausted) {
        f.setAiStatus(AiStatus.FAILED.name());
        f.setAiSummary(null);
    }
    int updated = mediaFileMapper.updateById(f);   // 受影响行数
    if (updated == 0) {
        // version 已变化：说明原任务在查询快照之后已经写入了新结果（成功/失败/仍在处理）。
        // 补偿调度器的这次判断已经过期，直接放弃本轮写入，不做任何覆盖，也不消耗台账记录。
        log.info("补偿写入被跳过（版本冲突，原任务已产生新状态），mediaId={}", f.getId());
        return;
    }
    if (exhausted) {
        failedTaskService.record(f.getId(), new AiAnalysisException("重试耗尽，判定失败", false), attempts);
        return;
    }
    aiService.asyncAnalyze(f.getId());
}
```

**关键点**：`updated == 0` 时不能重新查询最新记录再重试写入——因为这次补偿判断的前提（"20 分钟没更新，判定卡死"）已经不成立，原任务已经在这期间产生了新状态，重新判断属于新一轮职责，交给下一次 `@Scheduled` 扫描即可，不在本次内联重试。

### 2.4 影响面

- `AiService.asyncAnalyze()` 内所有 `mediaFileMapper.updateById(mediaFile)` 调用点，行为上不需要改动逻辑（乐观锁字段由 MyBatis-Plus 自动处理），但需要确认这些调用点里 `mediaFile` 对象全部来自本次执行内的 `selectById`，不存在跨线程/跨请求复用旧对象的情况——排查确认现状符合这一前提。
- `ContentTaskGate.resolveAnalysis()` 里的 `updateById` 调用同样自动获得乐观锁保护，属于额外收益。

---

## 三、问题 2：重试计数与真实执行结果未绑定

### 3.1 现状代码与问题机制

`AiService.asyncAnalyze()` 第 60 行：

```java
GateOutcome outcome = contentTaskGate.inAnalysisLock(contentHash, () -> { ... });
```

`ContentTaskGate.inAnalysisLock()`：

```java
public GateOutcome inAnalysisLock(String contentHash, Supplier<GateOutcome> action) {
    RLock lock = redissonClient.getLock(AnalysisTaskKeys.analysisLock(contentHash));
    boolean locked;
    try {
        locked = lock.tryLock(analysisLockWaitSeconds, TimeUnit.SECONDS);   // 最长等待 600 秒
    } catch (InterruptedException e) { ... return GateOutcome.DEFER; }
    if (!locked) {
        return GateOutcome.DEFER;   // 排队 600 秒仍未拿到锁，放弃
    }
    try {
        return action.get();
    } finally {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
```

`AnalysisCompensationScheduler.compensateOne()`：

```java
int attempts = (f.getAiAttempts() == null ? 0 : f.getAiAttempts()) + 1;   // ← 触发即计数，与结果无关
f.setAiAttempts(attempts);
f.setAiProcessAt(LocalDateTime.now());
// ……
aiService.asyncAnalyze(f.getId());   // ← 可能只是去排 600 秒锁又放弃（DEFER），也可能真正跑完
```

**问题机制**：`attempts+1` 发生在调用 `asyncAnalyze()` **之前**，且与这次调用最终是 `PROCEED`（真正执行完）、`REUSE`（复用结果）还是 `DEFER`（排队 600 秒后放弃）完全无关。如果原任务持锁时间长（比如卡在一次未触发内部重试上限的慢请求上），后续几轮补偿触发进来的都是「排队 600 秒 → DEFER」，什么都没真正执行，但 `attempts` 已经被消耗。按当前 `maxAttempts=3` 计算，**只需要 3 次这样的空等就会把重试次数耗尽**，导致原任务其实还没失败、只是还在正常执行，就被补偿调度器提前判定为永久失败并写入 `FAILED`。

这个问题与问题 1 组合后果更严重：`DEFER` 分支下 `asyncAnalyze()` 内部完全没有执行到 `mediaFileMapper.updateById`，所以不会触发乐观锁冲突检测，问题 1 的修复对这类空等不产生任何保护——需要单独修复。

### 3.2 修复方案：`asyncAnalyze()` 返回执行结果，调度器按结果决定是否计数

**思路**：把 `attempts` 递增的时机，从「决定要不要触发」挪到「已知这次触发的真实结果」之后，且只有 `PROCEED`（真正跑了一次完整流程且以失败告终）才算一次有效重试消耗；`REUSE` 不消耗（不需要）；`DEFER` 不消耗（这次什么都没做，等下一轮机会重新判断，不能让「陪跑排队」占用真正的重试机会）。

#### 3.2.1 `AiService.asyncAnalyze()` 改为返回 `GateOutcome`

现状返回值是 `void`：

```java
@Async("aiTaskExecutor")
public void asyncAnalyze(Long mediaId) { ... }
```

`@Async` 方法允许返回 `Future<T>`（需要用 `CompletableFuture` 包装才能被调用方感知结果），改造为：

```java
@Async("aiTaskExecutor")
public CompletableFuture<GateOutcome> asyncAnalyze(Long mediaId) {
    String contentHash = mediaService.contentHash(mediaId);
    GateOutcome outcome = contentTaskGate.inAnalysisLock(contentHash, () -> { /* 现有逻辑不变 */ });
    if (outcome == GateOutcome.DEFER) {
        log.info("分析锁让位, mediaId={} contentHash={}", mediaId, contentHash);
    }
    return CompletableFuture.completedFuture(outcome);
}
```

> 注意：原有全部调用方（`VideoAnalysisConsumer.onMessage`）不需要感知返回值变化，`void` 调用点忽略返回的 `Future` 即可，不影响现有语义。

#### 3.2.2 `AnalysisCompensationScheduler.compensateOne()` 按结果决定计数

```java
private void compensateOne(MediaFile f) {
    Long mediaId = f.getId();
    // 先刷新 ai_process_at 阻断「排队不执行→每分钟被重复扫到」的正反馈，但不在此处递增 attempts
    f.setAiProcessAt(LocalDateTime.now());
    int updated = mediaFileMapper.updateById(f);
    if (updated == 0) {
        log.info("补偿刷新时间戳被跳过（版本冲突），mediaId={}", mediaId);
        return;
    }

    CompletableFuture<GateOutcome> future = aiService.asyncAnalyze(mediaId);
    future.whenComplete((outcome, ex) -> {
        if (ex != null || outcome == GateOutcome.DEFER) {
            // 异常或让位：这次触发没有产生任何真实进展，不消耗 attempts，交下一轮重新判断
            log.warn("补偿触发未产生进展（DEFER/异常），mediaId={}, outcome={}", mediaId, outcome, ex);
            return;
        }
        if (outcome == GateOutcome.REUSE) {
            return;   // 复用他人结果，本来就不算失败重试，无需计数
        }
        // outcome == PROCEED：说明真正跑完了一次流程。
        // 若这次执行内部已经把状态写成 SUCCESS，此处的 attempts 增量不影响终态；
        // 若写成了 PROCESSING（asyncAnalyze 内部瞬时失败分支），说明这确实是一次有效的失败重试，需要计数。
        incrementAttemptsIfStillPending(mediaId);
    });
}

private void incrementAttemptsIfStillPending(Long mediaId) {
    MediaFile latest = mediaFileMapper.selectById(mediaId);
    if (latest == null || !AiStatus.PROCESSING.name().equals(latest.getAiStatus())) {
        return;   // 已经是 SUCCESS/FAILED 等终态，不需要补偿再计数
    }
    int attempts = (latest.getAiAttempts() == null ? 0 : latest.getAiAttempts()) + 1;
    latest.setAiAttempts(attempts);
    if (attempts >= maxAttempts) {
        latest.setAiStatus(AiStatus.FAILED.name());
        latest.setAiSummary(null);
    }
    int updated = mediaFileMapper.updateById(latest);
    if (updated == 0) {
        return;   // 版本冲突：这段时间内状态又被改写，放弃本次计数
    }
    if (attempts >= maxAttempts) {
        failedTaskService.record(mediaId, new AiAnalysisException("重试耗尽，判定失败", false), attempts);
    }
}
```

**关键设计点**：
- `attempts` 计数从 `compensateOne` 触发前挪到 `asyncAnalyze` 真正返回结果之后，且只在「确实执行完一次流程、仍以 `PROCESSING`（瞬时失败）收尾」时才计数，把「陪跑排队 600 秒」和「真正执行了一次失败」区分开。
- 这一步同时依赖问题 1 的乐观锁修复：`incrementAttemptsIfStillPending` 重新 `selectById` 拿最新快照再写，天然避免了旧快照覆盖问题，两个修复互相加固。

### 3.3 影响面

- `VideoAnalysisConsumer.onMessage()` 调用 `aiService.asyncAnalyze(mediaId)` 处返回值从 `void` 变为 `CompletableFuture<GateOutcome>`，消费者只做触发派发不关心结果，忽略返回值即可，**不需要改动**。
- 单元测试 `ContentTaskGateTest`（若存在覆盖 `asyncAnalyze` 调用路径的测试）需要同步适配新的返回类型签名。

---

## 四、问题 3：消费者内部重试改为指数退避

### 4.1 现状

`DeepSeekUtils.callWithRetry()` 与 `AliyunAsrUtils.audioToText()` 均为固定 2 秒重试：

```java
// DeepSeekUtils.java
int maxRetries = 3;
for (int i = 0; i < maxRetries; i++) {
    try {
        Request request = requestSupplier.get();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return content;
            } else {
                int code = response.code();
                if (code >= 500 || code == 408 || code == 429) {
                    Thread.sleep(2000);   // ← 固定间隔，无指数退避
                    continue;
                } else {
                    throw new AiAnalysisException("DeepSeek 请求被拒绝: " + lastError, false, AiFailStage.LLM);
                }
            }
        }
    } catch (IOException e) {
        lastError = e.getMessage();
        log.warn("[DeepSeek] 网络异常 ({}/{}): {}", i + 1, maxRetries, lastError);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
    }
}
```

```java
// AliyunAsrUtils.java —— 同样的固定间隔问题
int code = response.code();
if (code >= 500 || code == 408 || code == 429) {
    Thread.sleep(2000);
    continue;
}
```

**问题**：固定 2 秒重试对付「几秒钟内自愈」的抖动效果有限，遇到限流（429）或短时过载（5xx）持续几十秒的场景，3 次固定间隔重试（共等待 6 秒）大概率全部落空，直接把失败抛给上层（`AiFailStage.LLM`/`AiFailStage.ASR`，`retryable=true`），提前消耗补偿调度器的重试次数（即第三节修复的 `compensationAttempts`）。指数退避能让重试等待时间随轮次增长，更贴近网络抖动/限流窗口的实际持续时间，减少无效重试、把故障恢复窗口留给更长的等待。

### 4.2 修复方案：`Thread.sleep(2000)` 改为 `Thread.sleep(1_000L << i)`

两处改法一致，改动量小、风险低，且是本次计划中优先级第三高的问题（仅次于两个数据正确性问题），因为它直接决定系统在网络抖动发生时的第一道自愈能力强弱：

`DeepSeekUtils.java`：

```java
private String callWithRetry(Supplier<Request> requestSupplier) {
    int maxRetries = 3;
    String lastError = "";

    for (int i = 0; i < maxRetries; i++) {
        try {
            Request request = requestSupplier.get();
            log.info("[DeepSeek] 请求中 (第 {} 次尝试)...", i + 1);

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    // ……响应处理，返回 content ……
                    return content;
                } else {
                    String errBody = response.body() != null ? response.body().string() : "";
                    lastError = "HTTP " + response.code() + ": " + errBody;
                    log.warn("[DeepSeek] 失败 ({}/{}): {}", i + 1, maxRetries, lastError);

                    int code = response.code();
                    if (code >= 500 || code == 408 || code == 429) {
                        long backoffMs = 1_000L << i;   // 指数退避：i=0→1s, i=1→2s, i=2→4s
                        log.info("[DeepSeek] 触发退避，等待 {}ms 后重试", backoffMs);
                        Thread.sleep(backoffMs);
                        continue;
                    } else {
                        throw new AiAnalysisException("DeepSeek 请求被拒绝: " + lastError, false, AiFailStage.LLM);
                    }
                }
            }
        } catch (IOException e) {
            lastError = e.getMessage();
            log.warn("[DeepSeek] 网络异常 ({}/{}): {}", i + 1, maxRetries, lastError);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastError = "retry interrupted: " + e.getMessage();
            break;
        }
    }

    throw new AiAnalysisException("DeepSeek 请求失败，已重试 " + maxRetries + " 次: " + lastError, true, AiFailStage.LLM);
}
```

`AliyunAsrUtils.java` 同样改法：

```java
public String audioToText(String filePath) {
    File file = new File(filePath);
    if (!file.exists()) throw new AiAnalysisException("音频文件不存在: " + filePath, false, AiFailStage.FILE);

    int maxRetries = 3;
    String lastError = "";

    for (int i = 0; i < maxRetries; i++) {
        try {
            log.info("🎤 [ASR] 上传中 (第 {} 次尝试)...", i + 1);
            // ……构造请求、发起调用……
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    // ……解析返回文本……
                    return text;
                } else {
                    String errBody = response.body() != null ? response.body().string() : "";
                    lastError = "HTTP " + response.code() + ": " + errBody;
                    log.warn("⚠️ ASR 失败 ({}/{}): {}", i + 1, maxRetries, lastError);

                    int code = response.code();
                    if (code >= 500 || code == 408 || code == 429) {
                        long backoffMs = 1_000L << i;   // 指数退避：1s/2s/4s
                        log.info("🎤 [ASR] 触发退避，等待 {}ms 后重试", backoffMs);
                        Thread.sleep(backoffMs);
                        continue;
                    } else {
                        throw new AiAnalysisException("ASR 识别失败: " + lastError, false, AiFailStage.ASR);
                    }
                }
            }
        } catch (IOException e) {
            lastError = e.getMessage();
            log.warn("⚠️ 网络异常 ({}/{}): {}", i + 1, maxRetries, lastError);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastError = "线程中断: " + e.getMessage();
            break;
        }
    }

    throw new AiAnalysisException("ASR 最终失败（已重试 3 次）: " + lastError, true, AiFailStage.ASR);
}
```

两个工具类当前重试轮次上限均为 3（`maxRetries = 3`），退避序列固定为 **1s → 2s → 4s**，总等待时间从固定 6 秒（2s×3）变为 7 秒（1+2+4），基本等价但对持续性瞬时故障（如限流窗口跨越几秒到十几秒）的退避效果更好——第三次重试前已经多等待了 2 秒的额外窗口。

**范围限定**：本次不引入随机 Jitter。理由是这两处重试都发生在单个 JVM 进程内部，针对同一次业务请求的串行重试，不存在「多个客户端同时对同一个第三方 API 重试导致雷鸣效应」的场景（这种场景需要 Jitter 来错开多个客户端的重试时间点）。如果未来项目演进出多实例并发调用同一 LLM/ASR 接口的场景，需要重新评估补充 Jitter。

### 4.3 影响面

仅限两个工具类内部的 `Thread.sleep` 调用，不涉及方法签名或调用方变化，是本计划中改动面最小、风险最低的一项，可以独立于其他问题先行落地验证。

---

## 五、问题 4：多实例部署下补偿调度器无分布式协调

### 5.1 现状

```java
@Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
public void compensate() { ... }
```

`@Scheduled` 是纯本地 JVM 定时任务，多实例部署时每个实例各自独立扫描、各自独立触发，没有互斥机制。

### 5.2 修复方案：Redisson 分布式锁包裹整轮扫描

复用项目已有的 `RedissonClient` 依赖（`ContentTaskGate` 已经在用），不引入新的第三方库（如 ShedLock），保持依赖面最小：

```java
@Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
public void compensate() {
    RLock schedulerLock = redissonClient.getLock("lock:scheduler:analysis-compensation");
    boolean locked;
    try {
        locked = schedulerLock.tryLock(0, 50, TimeUnit.SECONDS);   // 抢不到立即放弃，锁 50s 自动释放兜底进程崩溃
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
    }
    if (!locked) {
        return;   // 其他实例正在跑本轮扫描，本实例跳过
    }
    try {
        LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofMinutes(thresholdMinutes));
        List<MediaFile> stalled = mediaFileMapper.selectStalledAnalysis(threshold, SCAN_LIMIT);
        if (stalled.isEmpty()) return;
        log.info("补偿调度器扫到 {} 条卡死记录", stalled.size());
        for (MediaFile f : stalled) {
            try {
                compensateOne(f);
            } catch (Exception e) {
                log.warn("单条补偿处理异常，mediaId={}, err={}", f.getId(), e.getMessage(), e);
                // 见问题 6：单条异常不能中断整轮循环
            }
        }
    } finally {
        if (schedulerLock.isHeldByCurrentThread()) schedulerLock.unlock();
    }
}
```

**锁租约 50 秒**小于扫描周期 60 秒，即便持锁实例崩溃未释放锁，最迟 50 秒后锁自动失效，不会导致补偿永久停摆。`tryLock(0, ...)` 表示不等待，抢不到立即返回，避免多实例都在等锁排队。

### 5.3 影响面

- 需要在 `AnalysisCompensationScheduler` 构造函数注入 `RedissonClient`（项目已作为 Spring Bean 存在，`ContentTaskGate` 同样注入方式）。
- 单实例部署（当前环境）下这段代码只是多一次「本地内必然成功」的 `tryLock`，性能开销可忽略，可以直接落地不等多实例部署时再补。

---

## 六、问题 5：`ai_attempts` 语义拆分

### 6.1 现状冲突

`DebugController.aiAnalyze()`（用户主动重新提交入口）：

```java
file.setAiAttempts(0);   // 用户点一次「重新分析」，计数清零
```

`AnalysisCompensationScheduler`（问题 2 修复后）：

```java
latest.setAiAttempts(attempts);   // 补偿调度器判定为一次有效失败重试后自增
```

同一个字段被两条意图完全不同的写路径共用：一条代表「给我一次新机会」，一条代表「系统自动重试的第几次」。用户在补偿判定即将耗尽前手动点「重新分析」，会让 `maxAttempts` 这个安全阀失效——卡死的任务只要用户反复手动重试就永远不会被最终判定为 `FAILED`。

### 6.2 修复方案：拆分为两个独立语义的字段

新增字段 `compensationAttempts`，专属补偿调度器写入；原有 `aiAttempts` 保留但语义收窄为「本轮提交的用户可见重试次数展示」，两者互不干扰。

#### 6.2.1 数据库迁移（与问题 1 的迁移合并到同一个文件，减少一次迁移执行）

`db/V6__add_media_file_version.sql` 追加：

```sql
ALTER TABLE media_files
  ADD COLUMN compensation_attempts INT NOT NULL DEFAULT 0 COMMENT '补偿调度器专属重试计数，不受用户手动重新提交影响';
```

#### 6.2.2 `entity/MediaFile.java`

```java
private Integer compensationAttempts;   // 补偿调度器专属计数，与 aiAttempts（用户可见展示）语义分离
```

#### 6.2.3 改动点

- `AnalysisCompensationScheduler.incrementAttemptsIfStillPending()` 中的计数和判定全部改用 `compensationAttempts`，不再读写 `aiAttempts`。
- `DebugController.aiAnalyze()` 用户重新提交时，`aiAttempts` 与 `compensationAttempts` **都清零**——用户主动重试应视为全新一轮，旧的补偿计数不应遗留。
- 前端展示逻辑（如需要显示"重试第几次"）改读 `aiAttempts`（用户视角的直观计数），后端安全阀判断只依赖 `compensationAttempts`。

### 6.3 影响面

- `MediaFileMapper.selectStalledAnalysis()` 查询条件不受影响（仍按 `ai_status` + `ai_process_at` 过滤）。
- 失败台账 `FailedAnalysisTaskService.record()` 的 `attempts` 参数改传 `compensationAttempts`，语义更准确地反映"这是补偿机制自动重试了几次后判定失败"。

---

## 七、问题 6、7：异常隔离与线程池拒绝防护

### 7.1 单条记录异常隔离

已在第五节 `compensate()` 示例代码中体现：`for` 循环内 `compensateOne(f)` 用 `try-catch` 包裹，单条记录处理异常（如该条记录关联的 `FailedAnalysisTaskService.record()` 罕见地抛出未捕获异常）只记录日志，不影响循环继续处理剩余记录。

### 7.2 线程池拒绝异常不应静默消耗计数

`ThreadPoolConfig.aiTaskExecutor()` 现状：

```java
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
```

`AbortPolicy` 在线程池（核心4/最大8/队列100）打满时对 `execute()` 调用方抛 `RejectedExecutionException`。Spring 的 `@Async` 方法在**任务提交阶段**（而不是方法体内部）被拒绝时，这个异常发生在 Spring 生成的代理逻辑里，业务代码的 try-catch 无法捕获，默认由 `AsyncUncaughtExceptionHandler` 处理（未配置自定义 Handler 时仅打印堆栈，不会以任何形式传导回调用方）。

结合问题 2 的修复方案，这个场景已经被自然规避：因为计数只在拿到 `CompletableFuture` 并等到 `whenComplete` 真正回调后才发生，如果任务在提交阶段就被拒绝，`asyncAnalyze()` 调用本身会直接抛出异常（同步抛出，而不是异步结果里的异常），这个异常发生在 `AnalysisCompensationScheduler.compensateOne()` 的调用点，会被第五节 `compensate()` 循环里新增的 `try-catch` 捕获并记录日志，**不会走到 `incrementAttemptsIfStillPending`，不会消耗 `compensationAttempts`**，问题 7 描述的"计数已加但任务未提交"场景在新方案下不再成立。

**结论**：问题 6、7 不需要单独的修复代码，是问题 2、4 的修复方案自然覆盖的连带收益，验证阶段需要专门设计用例确认这一点（见第十节验证方式第 7 条）。

---

## 八、问题 8：`selectStalledAnalysis` 补充索引（可延后）

新增复合索引，避免大数据量下全表扫描：

```sql
-- 并入 db/V6__add_media_file_version.sql
CREATE INDEX idx_ai_status_process_at ON media_files(ai_status, ai_process_at, id);
```

当前 `media_files` 表数据量未达到需要紧急处理的规模，此项可以和 V6 迁移一起顺带做，不需要单独排期或验证。

---

## 九、文件变更清单

### 新增

```
db/V6__add_media_file_version.sql   # version 字段 + compensation_attempts 字段 + 补充索引，三项合并一次迁移
```

### 修改

```
utils/DeepSeekUtils.java                       # 固定 2s 重试 → 指数退避 1s/2s/4s
utils/AliyunAsrUtils.java                       # 同上
entity/MediaFile.java                          # +version(@Version) +compensationAttempts
service/AiService.java                         # asyncAnalyze 返回值 void → CompletableFuture<GateOutcome>
service/AnalysisCompensationScheduler.java     # 分布式锁包裹 compensate；compensateOne 按执行结果计数；单条异常隔离
controller/DebugController.java                # 用户重新提交时 aiAttempts 与 compensationAttempts 均清零
service/FailedAnalysisTaskService.java         # record() 的 attempts 参数语义改为 compensationAttempts（签名不变，仅调用方传参来源变化）
plan/AI_ANALYSIS_COMPENSATION_PLAN.md          # 追加引用本计划书，说明后续加固关系
```

### 不改

```
consumer/VideoAnalysisConsumer.java            # 忽略 asyncAnalyze 新返回值，调用方式不变
consumer/VideoAnalysisDlqConsumer.java
ContentTaskGate.java                           # 锁语义不变，updateById 自动获得乐观锁保护属于无感收益
mapper/MediaFileMapper.java                    # selectStalledAnalysis 查询条件不变
```

---

## 十、验证方式

1. **编译**：`compile-server` 通过，重点确认 `asyncAnalyze` 返回值类型变化未破坏调用方。
2. **指数退避（问题 3）**：临时让 DeepSeek/ASR 返回 500 三次以上，观察日志中两次重试间隔应依次为约 1s→2s→4s，总耗时约 7 秒。
3. **丢失更新回归（问题 1）**：人工构造竞态——在补偿调度器 `selectStalledAnalysis` 查出记录后、`updateById` 执行前，用另一条线程/手动 SQL 提前把该记录写成 `SUCCESS`，确认补偿调度器的 `updateById` 返回 0 且不覆盖已写入的成功结果，日志出现"版本冲突"提示。
4. **重试计数不误耗尽（问题 2）**：调小 `ai.analysis-lock-wait-seconds` 制造长时间锁竞争，让若干次补偿触发都落入 `DEFER`，确认 `compensationAttempts` 未增长；再验证「真正执行且以 `PROCESSING` 收尾」的一次触发确实计数 +1。
5. **多实例互斥（问题 4）**：本地起两个后端进程实例（不同端口，共享同一 Redis/MySQL），同一时刻触发补偿扫描，确认只有一个实例的日志打出"扫到 N 条卡死记录"，另一实例静默跳过。
6. **计数语义隔离（问题 5）**：制造一条 `compensationAttempts` 接近上限的记录，用户走 `DebugController.aiAnalyze` 手动重新提交，确认 `aiAttempts` 和 `compensationAttempts` 均清零，重新计入新一轮。
7. **异常隔离 + 线程池拒绝（问题 6/7）**：批量构造超过 `SCAN_LIMIT` 的卡死记录，同时让 `aiTaskExecutor` 队列打满触发 `RejectedExecutionException`，确认该异常被 `compensate()` 循环内 try-catch 捕获、不消耗 `compensationAttempts`、且不影响同批次其余记录的处理。
8. **索引生效（问题 8）**：`EXPLAIN` 确认 `selectStalledAnalysis` 走 `idx_ai_status_process_at` 索引而非全表扫描。

---

## 十一、风险与注意事项

| 风险 | 对策 |
|------|------|
| `@Version` 字段引入后，任何遗漏刷新 `version` 就复用旧对象再 `updateById` 的代码会静默更新失败（返回 0） | 排查确认现状所有 `updateById` 调用点的 `MediaFile` 对象均来自本次执行内的 `selectById`，无跨请求缓存复用；新增代码需保持这一前提 |
| `asyncAnalyze` 返回值类型变化是公有方法签名变更 | 全项目搜索所有调用点（当前仅 `VideoAnalysisConsumer` 与补偿调度器两处）确认均兼容 |
| 分布式锁租约 50 秒 < 扫描周期 60 秒，若单轮 `compensateOne` 循环（最多 100 条）耗时超过 50 秒，锁会提前释放导致下一轮扫描与本轮并发 | 现状单条 `compensateOne` 是异步触发（`asyncAnalyze` 立即返回 Future，不等待执行完成），循环本身耗时应在毫秒级，风险很低；若未来 `compensateOne` 变为同步等待需重新评估租约时长 |
| `compensationAttempts` 与 `aiAttempts` 双字段增加了状态复杂度 | 两字段各自单一写路径（前者仅补偿调度器写，后者仅用户提交入口写），职责边界清晰，不存在第三条写路径 |
| 指数退避序列变化（6 秒→7 秒总等待时间） | 影响极小，且原有 `maxRetries=3` 上限不变，不影响 `AiFailStage.LLM/ASR` 的可重试判定逻辑 |
| 指数退避未加 Jitter | 当前重试发生在单进程内部串行请求，不存在多客户端雷鸣效应场景，暂不需要；未来若演进为多实例并发调用同一第三方接口需重新评估 |

---

## 十二、实施顺序

1. `utils/DeepSeekUtils.java` / `utils/AliyunAsrUtils.java` 指数退避改造（问题 3）——改动面最小、风险最低，独立先行落地并验证。
2. `db/V6__add_media_file_version.sql`（`version` + `compensation_attempts` + 索引，一次迁移三件事）→ `MediaFile` 实体同步加字段。
3. `AiService.asyncAnalyze` 返回值改造（问题 2 的前提）。
4. `AnalysisCompensationScheduler`：分布式锁（问题 4）+ 按结果计数（问题 2）+ 单条异常隔离（问题 6/7）一次性改完，三者互相依赖不易拆开验证。
5. `DebugController` 双字段清零（问题 5）。
6. `FailedAnalysisTaskService` 调用方传参来源确认。
7. 编译验证（`compile-server`）+ 按「十、验证方式」逐条回归。
8. 同步更新 `AI_ANALYSIS_COMPENSATION_PLAN.md`，追加指向本计划书的说明。
