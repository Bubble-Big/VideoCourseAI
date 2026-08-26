# AI 分析补偿式重试改造计划书（消费线程解耦 + DB 状态机定时补偿）

> 状态：**拟实施 / 待评审**（最后更新 2026-08-25）。
>
> 本计划解决的问题：`VideoAnalysisConsumer` 同步消费把 15 分钟级长任务（FFmpeg + ASR + DeepSeek）压在 RocketMQ 监听线程上，导致消费线程被长时间占用、并发失控。改造目标是把「触发」与「执行/重试」解耦。

---

## 一、背景与问题

### 1.1 现状：同步消费阻塞监听线程

当前 `VideoAnalysisConsumer.onMessage` 直接同步调用 `aiService.asyncAnalyze(mediaId)`（`VideoAnalysisConsumer.java:51`），该方法内部串行跑 FFmpeg 提取音频 → ASR 转写 → DeepSeek 总结，最长可达 15 分钟。后果：

1. **消费线程被长期占用**：RocketMQ 默认 `ConsumeMode.CONCURRENTLY`，每个分析任务占住一个消费线程 15 分钟；在途任务峰值 ≈ 全局限流 30 次/分 × 15 分钟 = 450，远超消费线程数（默认 20~64），后续消息在 broker 排队积压。
2. **并发失控**：原本为 AI 分析设计的 `aiTaskExecutor`（核心4/最大8/队列100 + 背压）对 AI 分析失效，并发数由「消费线程数」而非「线程池上限」决定。
3. **职责错位**：消费线程契约是「快进快出」（拉消息→派发→ACK），却被塞进重活。

### 1.2 根因：RocketMQ 重投是「同步结果驱动」的

`RocketMQListener` 底层是 `MessageListenerConcurrently`，语义是「`onMessage` 正常返回 → ACK；抛异常 → RECONSUME_LATER 重投」。也就是说，「是否重试」只能由 onMessage 的**同步返回/抛异常**表达，因此只有两条路：

- **同步阻塞跑任务** → 能拿到结果 → 能表达「成功/重试」（当前方案，代价是阻塞监听线程）。
- **异步丢线程池立即 ACK** → 拿不到结果 → 只能无条件 ACK，丢重试（改造前的旧方案，commit `5af2a31` 之前的版本）。

**结论**：只要「重试」依赖 MQ 的 `reconsumeTimes` 重投，就必然在「阻塞」与「放弃重试」之间二选一。要两全，必须把「重试」从 MQ 层下沉到应用层。

### 1.3 目标

1. **消费线程快进快出**：`onMessage` 只做触发派发，立即 ACK，不再阻塞。
2. **失败仍可重试**：用「DB 状态机 + 定时补偿」取代 MQ `reconsumeTimes` 重投。
3. **并发受控**：执行回到 `aiTaskExecutor`（8 上限 + 队列 100 背压）。

---

## 二、方案设计

### 2.1 总体思路：MQ 只做「触发信号」，执行与重试下放

- **MQ**：消息只是「触发信号」，消费线程收到即派发、立即 ACK，绝不执行重活。
- **执行**：回到 `aiTaskExecutor`（`@Async`）。
- **重试**：由「DB 状态机 + 定时补偿」负责——瞬时失败不靠 MQ 重投，而是保持 `PROCESSING` 并刷新时间戳，由定时任务扫「卡死/超时」记录重新触发。

### 2.2 状态字段新增

`media_files` 表新增两个字段（不复用 `upload_time`，其语义是「上传时间」且已被上传链路占用）：

```sql
ai_process_at DATETIME NULL COMMENT '最近一次 AI 分析尝试时间（提交/开始/失败时刷新）',
ai_attempts    INT NOT NULL DEFAULT 0 COMMENT 'AI 分析已尝试次数（重试上限判定）'
```

**为什么需要**：
- 定时补偿要判断「PENDING/PROCESSING 卡死」，必须有时间戳判定「超时」。
- `ai_attempts` 用于重试上限，避免瞬时故障导致无限重试烧钱。

### 2.3 状态流转

```
AI 分析： NONE → PENDING → PROCESSING → SUCCESS / FAILED
                │ 提交侧置PENDING     │ asyncAnalyze 异步开始
                │ +ai_process_at=now │ +ai_attempts+1 +ai_process_at=now
                │ +ai_attempts=0     │
                │                    │ 瞬时失败：保持 PROCESSING + ai_process_at=now（重新计时）
                │                    │ 永久失败：落 FAILED + 写台账
                │                    ▼
                └─── 定时补偿每 1min 扫「ai_status IN (PENDING,PROCESSING)
                        AND ai_process_at < now() - 20min」
                        ├─ ai_attempts < 3 → 重新触发 asyncAnalyze
                        └─ ai_attempts >= 3 → 落 FAILED + 写台账
```

**超时阈值 20min 的关键约束**：必须大于最长正常执行时间（15 分钟），否则会误杀仍在正常跑的任务。20min 可配置。

### 2.4 重试策略

`ai_attempts` 由**补偿触发侧统一 +1**（`asyncAnalyze` 内不计数）：提交时置 0，每次补偿触发 +1，达 3 落 FAILED。

| 环节 | 行为 |
|------|------|
| 正常执行中 | `ai_process_at` = 开始时间，耗时 ≤ 15min，不会被补偿扫到 |
| 瞬时失败 | 保持 `PROCESSING`，`ai_process_at = now()`（从失败时刻重新计时），20min 后补偿重新触发 |
| 进程崩溃/卡死 | `ai_process_at` 停在开始时间，20min 后被补偿扫到并重新触发 |
| 触发频率限制 | 补偿触发前先刷新 `ai_process_at`，同一 contentHash 每 20min 最多触发一次，队列不堆积 |
| 重试上限 | 补偿触发侧 `ai_attempts+1 >= 3` 时不再触发，直接落 `FAILED` + 台账 |

> 固定 20min 间隔是「固定间隔退避」的最简实现；若需指数退避，可让补偿间隔随 `ai_attempts` 递增（如 `20min × attempts`），本期不做。

---

## 三、逐文件改造

### 3.1 `entity/MediaFile.java`

新增两个字段：

```java
private LocalDateTime aiProcessAt;  // 最近一次 AI 分析尝试时间
private Integer aiAttempts;         // 已尝试次数（重试上限判定）
```

### 3.2 `config/ThreadPoolConfig.java`

拒绝策略 `CallerRunsPolicy` → `AbortPolicy`：

```java
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
```

**原因**：`CallerRunsPolicy` 会在队列满（100）时把任务回退到**调用线程（监听线程）同步执行**，重新引入阻塞，与本改造「监听线程永不阻塞」的目标直接冲突。改 `AbortPolicy` 后队列满抛 `RejectedExecutionException`，由 `onMessage` 捕获并吞掉（状态仍是 PENDING，交给定时补偿兜底）。

### 3.3 `controller/DebugController.java`

`aiAnalyze` 置 `PENDING` 时追加两个字段（`DebugController.java:89-91` 附近）：

```java
file.setAiStatus(AiStatus.PENDING.name());
file.setAiSummary(null);
file.setAiProcessAt(LocalDateTime.now());  // 首次触发时间
file.setAiAttempts(0);                     // 新的一轮，重置计数
```

> 回滚分支（`catch`）无需恢复这两个字段：回滚后 `aiStatus` 回到非 PENDING/PROCESSING，补偿只扫 PENDING/PROCESSING，残留无害。

### 3.4 `service/AiService.java`（核心改造）

`asyncAnalyze` 做三处改造：**加 `@Async`、锁内移、异常内部消化（不再上抛）**。

```java
@Async("aiTaskExecutor")
public void asyncAnalyze(Long mediaId) {
    MediaFile mediaFile = null;
    // ① 内容级锁：从 onMessage 移到这里（执行在异步线程，锁必须跟随执行线程）
    String contentHash = mediaService.contentHash(mediaId);
    RLock lock = redissonClient.getLock(AnalysisTaskKeys.analysisLock(contentHash));
    if (!lock.tryLock()) {
        log.info("分析任务已在执行，跳过 mediaId={} contentHash={}", mediaId, contentHash);
        return;   // 同一内容已在跑（并发触发 / 补偿重复），跳过
    }
    try {
        mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            throw new AiAnalysisException("文件不存在: " + mediaId, false);
        }
        // ② 进入处理态：只置 PROCESSING + 刷新时间戳（ai_attempts 由补偿触发侧统一 +1，这里不计数）
        mediaFile.setAiStatus(AiStatus.PROCESSING.name());
        mediaFile.setAiProcessAt(LocalDateTime.now());
        mediaFileMapper.updateById(mediaFile);

        // …… 原有逻辑：结果复用 → 转写 → 总结 → 写 SUCCESS → 登记归属 → 删缓存 ……

    } catch (Exception e) {
        if (e instanceof AiAnalysisException ae && !ae.isRetryable()) {
            // 永久失败：落 FAILED + 台账，不再上抛（@Async 异常到不了消费层）
            if (mediaFile != null) markFailed(mediaFile, e);
            failedTaskService.record(mediaId, e);
            return;
        }
        // 瞬时失败 / 未预期异常：保持 PROCESSING + 刷新时间戳，等定时补偿重试
        if (mediaFile != null) {
            mediaFile.setAiStatus(AiStatus.PROCESSING.name());
            mediaFile.setAiProcessAt(LocalDateTime.now());
            mediaFileMapper.updateById(mediaFile);
        }
        if (e instanceof AiAnalysisException ae) {
            failedTaskService.record(mediaId, ae);
        }
        log.warn("AI 分析瞬时失败，保持 PROCESSING 等待补偿重试, mediaId={}, err={}", mediaId, e.getMessage());
    } finally {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
```

**关键语义变化**：

- `@Async` 隔离了异常传播，`asyncAnalyze` 的异常**不再能抛回消费层**，必须**内部消化**：永久失败落 `FAILED`，瞬时失败保持 `PROCESSING` + 刷新 `ai_process_at`，把「重试决策」交给补偿调度器。
- 锁从 `onMessage` 移到 `asyncAnalyze` 内部，锁的生命周期跟随**执行线程**（而非监听线程），`analysisLock → contextLock` 的嵌套顺序不变。
- **去重主力在「触发侧」而非「执行侧」**：`ai_attempts` 由补偿触发侧统一 +1（`asyncAnalyze` 不计数），补偿触发前先刷新 `ai_process_at`（见 3.6），保证同一 contentHash 在队列里最多 3 个任务、每 20min 才可能新增一个；内容锁 `tryLock` 降级为「执行时防并发的最后兜底」，不再是去重主力。

新增 `markFailedFinal(Long mediaId)`（供补偿调度器/DLQ 兜底调用，绕过可重试判断直接落 FAILED）：

```java
public void markFailedFinal(Long mediaId) {
    MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
    if (mediaFile == null) return;
    markFailed(mediaFile, new AiAnalysisException("重试耗尽，判定失败", false));
}
```

### 3.5 `consumer/VideoAnalysisConsumer.java`（大幅简化）

```java
@Override
public void onMessage(AnalysisTaskMsg msg) {
    Long mediaId = msg.getMediaId();
    log.info("收到分析任务, mediaId={}", mediaId);
    try {
        // 只做触发派发：@Async 立即返回，监听线程快进快出
        aiService.asyncAnalyze(mediaId);
    } catch (RejectedExecutionException e) {
        // 线程池队列满：吞掉并 ACK，状态仍是 PENDING，交给定时补偿兜底
        log.warn("分析任务派发被拒绝（线程池过载），等待补偿重试, mediaId={}", mediaId);
    }
}
```

- 移除：内容锁（移入 `asyncAnalyze`）、`failedTaskService` 依赖、异常上抛逻辑。
- `maxReconsumeTimes = 2` 保留在注解上作防御（消息反序列化等异常仍会重投），但**不再是核心重试机制**。

### 3.6 `service/AnalysisCompensationScheduler.java`（新增）

```java
@Component
public class AnalysisCompensationScheduler {

    private static final int MAX_ATTEMPTS = 3;                    // 最大尝试次数
    private static final Duration STALL_THRESHOLD = Duration.ofMinutes(20); // 卡死阈值

    private final MediaFileMapper mediaFileMapper;
    private final AiService aiService;               // 注入 bean，@Async 才生效（勿用 this 调用）
    private final FailedAnalysisTaskService failedTaskService;

    public AnalysisCompensationScheduler(MediaFileMapper mediaFileMapper,
                                         AiService aiService,
                                         FailedAnalysisTaskService failedTaskService) {
        this.mediaFileMapper = mediaFileMapper;
        this.aiService = aiService;
        this.failedTaskService = failedTaskService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void compensate() {
        LocalDateTime threshold = LocalDateTime.now().minus(STALL_THRESHOLD);
        List<MediaFile> stalled = mediaFileMapper.selectStalledAnalysis(threshold, 100);
        for (MediaFile f : stalled) {
            // 触发侧：先 +1 计数并刷新时间戳，再决定「触发」还是「落 FAILED」。
            // 刷新 ai_process_at 是关键——切断「排队不执行 → 补偿每分钟重复触发」的正反馈，
            // 把同一 contentHash 的触发频率锁死为「每 20min 一次」。
            int attempts = (f.getAiAttempts() == null ? 0 : f.getAiAttempts()) + 1;
            f.setAiAttempts(attempts);
            f.setAiProcessAt(LocalDateTime.now());
            if (attempts >= MAX_ATTEMPTS) {
                // 重试耗尽 → 落 FAILED + 台账
                f.setAiStatus(AiStatus.FAILED.name());
                f.setAiSummary("❌ 分析失败，请稍后重试");
                mediaFileMapper.updateById(f);
                failedTaskService.record(f.getId(), new AiAnalysisException("重试耗尽", false));
                continue;
            }
            mediaFileMapper.updateById(f);       // 先落 attempts + ai_process_at
            aiService.asyncAnalyze(f.getId());  // 再重新触发（@Async 异步执行）
        }
    }
}
```

**多实例安全**：多个实例同时扫到同一批记录时，各自都会先 `ai_attempts + 1` 并刷新 `ai_process_at`；即便都触发了 `asyncAnalyze`，内部内容锁 `tryLock()` 也保证只有一实例真正执行，其余 `tryLock` 失败跳过（多 +1 的 attempts 由下一次「耗尽判定」兜底，不会无限重试）。

### 3.7 `mapper/MediaFileMapper.java`

新增卡死记录查询：

```java
@Select("SELECT * FROM media_files WHERE ai_status IN ('PENDING','PROCESSING') " +
        "AND ai_process_at < #{threshold} ORDER BY ai_process_at ASC LIMIT #{limit}")
List<MediaFile> selectStalledAnalysis(@Param("threshold") LocalDateTime threshold,
                                      @Param("limit") int limit);
```

### 3.8 `ServerApplication.java`

新增 `@EnableScheduling`（当前未启用；`@EnableAsync` 已在 `ThreadPoolConfig` 上）。

### 3.9 `consumer/VideoAnalysisDlqConsumer.java`（新增，防御性兜底）

改造后 MQ 重投基本退出主流程，DLQ 触发概率低，但保留作「消息消费异常」最后防线：

```java
@Component
@RocketMQMessageListener(topic = "%DLQ%video-group", consumerGroup = "video-group-dlq")
public class VideoAnalysisDlqConsumer implements RocketMQListener<AnalysisTaskMsg> {
    // 收到死信 → aiService.markFailedFinal(msg.getMediaId())，避免 aiStatus 永久卡 PENDING/PROCESSING
}
```

> 此项同时补齐 CLAUDE.md「已知陷阱」里长期挂账的「AI 分析死信无人兜底」。

### 3.10 `resources/application.properties`

新增补偿参数（阈值 / 上限 / 扫描间隔可配，当前用代码常量亦可）：

```properties
ai.compensation.threshold-minutes=20
ai.compensation.max-attempts=3
```

### 3.11 SQL 迁移

新增 `db/V4__add_ai_compensation.sql`（沿用现有 V 前缀命名；若项目未用 Flyway，需在 mysql-media 容器手动执行）：

```sql
ALTER TABLE media_files
  ADD COLUMN ai_process_at DATETIME NULL COMMENT '最近一次 AI 分析尝试时间',
  ADD COLUMN ai_attempts    INT NOT NULL DEFAULT 0 COMMENT 'AI 分析已尝试次数';

-- 历史遗留的 PENDING/PROCESSING 记录回填时间戳，避免 ai_process_at 为 NULL 被补偿查询漏扫
UPDATE media_files SET ai_process_at = NOW()
 WHERE ai_status IN ('PENDING','PROCESSING') AND ai_process_at IS NULL;
```

---

## 四、改造后时序

```
提交侧 DebugController.aiAnalyze
  ① setIfAbsent(analysis:active:{contentHash}, 30s)   —— 内容级幂等
  ② 双层限流 requireAiQuota(userId)
  ③ 置 PENDING + ai_process_at=now + ai_attempts=0
  ④ 发 MQ（携带 contentHash）→ 立即返回
        │ 异步
        ▼
消费侧 VideoAnalysisConsumer.onMessage          ← 毫秒级，快进快出
  ⑤ aiService.asyncAnalyze(mediaId)              —— @Async 提交 aiTaskExecutor，立即 ACK
        │（异步线程）
        ▼
AiService.asyncAnalyze
  ⑥ tryLock(lock:analysis:{contentHash})          —— 执行时兜底（非去重主力）
  ⑦ 置 PROCESSING + ai_process_at=now
  ⑧ 结果复用 → 转写(锁 + 归属复用) → summary → SUCCESS → 释放锁
        │ 瞬时失败：保持 PROCESSING + ai_process_at=now
        ▼
AnalysisCompensationScheduler（每 1min）
  ⑨ 扫 PENDING/PROCESSING 且 ai_process_at 超 20min
       ├─ 先 ai_attempts+1 + ai_process_at=now（触发频率限制 + 计数）
       ├─ attempts < 3 → asyncAnalyze 重新触发
       └─ attempts >= 3 → 落 FAILED + 台账
```

---

## 五、文件变更清单

### 新增

```
service/AnalysisCompensationScheduler.java     # 定时补偿：扫卡死记录重新触发 / 落 FAILED
consumer/VideoAnalysisDlqConsumer.java         # DLQ 兜底（同时补已知陷阱）
db/V4__add_ai_compensation.sql                 # 加 ai_process_at / ai_attempts + 回填
```

### 修改

```
entity/MediaFile.java                          # +aiProcessAt +aiAttempts
config/ThreadPoolConfig.java                   # 拒绝策略 CallerRuns → AbortPolicy
controller/DebugController.java                # 置 PENDING 时写新字段
service/AiService.java                         # @Async + 锁内移 + 异常内部消化 + markFailedFinal
consumer/VideoAnalysisConsumer.java            # 简化为触发派发 + ACK
mapper/MediaFileMapper.java                    # +selectStalledAnalysis
ServerApplication.java                         # +@EnableScheduling
resources/application.properties               # +补偿参数
CLAUDE.md / ARCHITECTURE.md                    # 同步「同步消费 → 触发+补偿」链路说明
```

---

## 六、验证方式

1. **监听线程快进快出**：日志确认 `onMessage` 耗时毫秒级，不再随视频时长增长。
2. **正常链路回归**：提交 → PENDING → PROCESSING → SUCCESS，前端 3s 轮询无感知（前端改动为零）。
3. **瞬时失败重试**：临时让 DeepSeek 返回 500 → `asyncAnalyze` 保持 PROCESSING + 刷新 `ai_process_at` → 20min 后补偿调度器重新触发 → 恢复后 SUCCESS。
4. **重试上限**：连续失败 3 次 → 补偿调度器 `markFailedFinal` 落 FAILED + 写台账，前端显示受控文案。
5. **进程崩溃恢复**：分析中途 kill 进程 → 重启后补偿扫到 PROCESSING 超时记录 → 重新触发。
6. **并发防重**：同 contentHash 多消息并发 → 内容锁 `tryLock` 只跑一次；多实例补偿同扫一批 → 同样只跑一次。
7. **队列满**：模拟线程池队列打满 → `onMessage` 吞 `RejectedExecutionException` 正常 ACK → 补偿兜底重试。
8. **DLQ 兜底**：构造消息异常进 `%DLQ%video-group` → DLQ 消费者落 FAILED，前端不无限转圈。

---

## 七、风险与注意事项

| 风险 | 对策 |
|------|------|
| `@Async` 隔离异常传播 | `asyncAnalyze` 必须**内部消化所有异常**（永久→FAILED，瞬时→PROCESSING+时间戳），不能有未捕获异常逃逸 |
| `CallerRuns → AbortPolicy` 队列满丢派发 | `onMessage` 捕获 `RejectedExecutionException` 吞掉，状态仍 PENDING，补偿兜底；不再回退监听线程 |
| 补偿阈值误杀正常任务 | 阈值 20min 必须 > 最长执行 15min，且可配置 |
| 多实例补偿重复触发 | 复用内容锁 `tryLock` 兜底，调度器本身不加锁 |
| `ai_process_at` 为 NULL 的历史 PENDING/PROCESSING 漏扫 | 迁移脚本回填 `NOW()` |
| 锁生命周期变化 | 锁从监听线程移到执行线程，`analysisLock → contextLock` 嵌套顺序不变，无死锁 |
| `@Async` 自调用失效 | 补偿调度器注入 `AiService` bean 调用（勿用 `this`）；`onMessage` 亦经 bean 调用 |
| 重试烧钱 | `ai_attempts` 上限 3 + 结果复用/内容锁，同一内容最多完整跑 3 次 |
| 同一 contentHash 任务在队列堆积 | 补偿触发侧先 `attempts+1` + 刷新 `ai_process_at`，触发频率锁死为每 20min 一次、上限 3 次；队列里同一 contentHash 恒为 O(1) 个任务 |

---

## 八、与现有机制的衔接

| 现有机制 | 改造后状态 |
|----------|-----------|
| 提交侧幂等键 `analysis:active` | 不变（仍在提交侧防重复提交） |
| 内容锁 `lock:analysis` | 保留，移到 `asyncAnalyze` 内部，降级为执行时防并发兜底（去重主力变为补偿触发侧的计数 + 频率限制） |
| 转写锁 + 归属复用 | 不变 |
| 双层限流 | 不变（仍在提交侧） |
| 失败台账 `failed_analysis_task` | 保留，永久失败/瞬时失败/重试耗尽均写台账 |
| MQ `reconsumeTimes` 重投 | 退化为防御性机制（仅消息异常触发），核心重试交给补偿 |
| DLQ 兜底 | 新增消费者，补已知陷阱 |

---

## 九、实施顺序

1. SQL 迁移（`V4` 加字段 + 回填）→ 改 `MediaFile` 实体。
2. `ThreadPoolConfig` 拒绝策略 → `AbortPolicy`；`ServerApplication` 加 `@EnableScheduling`。
3. `AiService`：`@Async` + 锁内移 + 异常内部消化 + `markFailedFinal`。
4. `VideoAnalysisConsumer` 简化；`DebugController` 写新字段。
5. `MediaFileMapper` + `AnalysisCompensationScheduler`。
6. `VideoAnalysisDlqConsumer` + `application.properties` 参数。
7. 编译验证（`compile-server`）+ 按「六、验证方式」回归。
8. 同步 `CLAUDE.md` / `ARCHITECTURE.md`。
