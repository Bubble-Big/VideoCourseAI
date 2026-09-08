# DOVideo-AI 视频分析链路深度解析

## 一、整体架构概览

DOVideo-AI 采用 **异步 MQ 驱动 + 多阶段 Agent 编排** 的架构，将视频分析任务分解为：
1. **任务提交与分发** — `AnalysisController` + `AnalysisDispatchService`
2. **消息消费与编排** — `VideoAnalysisConsumer` (RocketMQ)
3. **视频信息提取** — `VideoContextService` (ASR + OCR 并行)
4. **AI Agent 分析** — `AgentLoopService` (Planner → Executor → Critic 闭环)

---

## 二、视频信息提取链路（VideoContext 构建）

### 2.1 入口与触发

**流程起点**：`AiService.asyncAnalyze()`
```java
// AiService.java:101
VideoContext videoContext = resolveContext(mediaFile, userGoal, traceId, resolvedMode);
```

### 2.2 复用机制（三级检查）

在真正提取前，系统会按以下顺序尝试复用已有结果：

#### 第一级：本 mediaId 检查点复用
```java
// AiService.java:148
VideoContext checkpoint = checkpointService.loadContext(mediaFile.getId());
if (checkpoint != null) {
    telemetry.increment(traceId, "contextCheckpointHits", 1);
    return new VideoContext(checkpoint.source(), userGoal, checkpoint.segments());
}
```
- **触发条件**：当前 mediaId 曾经提取过上下文
- **适用场景**：同一视频换个分析目标重新提交

#### 第二级：内容级归属复用
```java
// AiService.java:154-156
String contentHash = AnalysisTaskKeys.normalizeContentHash(
        mediaFile.getId(), mediaService.contentHash(mediaFile.getId()));
VideoContext reused = reuseContentContext(mediaFile, userGoal, traceId, contentHash);
```
- **触发条件**：不同 mediaId 但 MD5 相同（重复上传）
- **Redis 键**：`context:owner:{contentHash}` → 归属 mediaId
- **有效期**：7 天
- **适用场景**：多用户上传同一视频，或同一用户重复上传

#### 第三级：内容级分布式锁
```java
// AiService.java:160-184
RLock contextLock = redissonClient.getLock(AnalysisTaskKeys.contextLock(contentHash));
boolean locked = contextLock.tryLock(CONTEXT_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
```
- **锁等待时间**：5 分钟（`CONTEXT_LOCK_WAIT_SECONDS = 300`）
- **抢锁失败**：抛异常交 RocketMQ 重投，等首个持锁任务完成后复用
- **防重复**：同一视频并发提交时，只有一个消费者真正执行 ASR+OCR

---

### 2.3 真正构建流程

当三级复用全部未命中时，进入 `VideoContextService.build()`：

#### 阶段 1：并行分支启动
```java
// VideoContextService.java:79-88
Future<BranchResult<TranscriptSegment>> transcriptFuture = submitBranch(
        asrExecutor,
        branchesFinished,
        () -> transcriptionService.transcribe(readableVideoPath, workDir.resolve("audio"), traceId));

Future<BranchResult<FramePart>> frameFuture = submitBranch(
        ocrExecutor,
        branchesFinished,
        () -> extractKeyFrames(readableVideoPath, workDir.resolve("frames"), traceId, uploadedEvidenceFrames));
```

**关键特性**：
- **独立线程池**：`asrExecutor` 和 `ocrExecutor` 各自隔离，避免相互阻塞
- **容错设计**：单路失败不影响另一路，最终合并时只要有一路成功即可
- **超时控制**：总时间预算 60 分钟，超时则取消所有分支

---

#### 阶段 2：ASR 分支（语音转文字）

**执行服务**：`SegmentedTranscriptionService.transcribe()`

**处理流程**：
1. **音频提取**：FFmpeg 从视频中提取音频（MP3 格式）
   ```bash
   ffmpeg -i <视频路径> -vn -acodec mp3 -ar 16000 -ac 1 <输出.mp3>
   ```

2. **时长检测**：获取音频时长，超过阈值则分段处理
   ```java
   double durationSeconds = detectDuration(audioPath);
   if (durationSeconds > SEGMENT_DURATION_SECONDS) {
       return transcribeSegmented(audioPath, durationSeconds, traceId);
   }
   ```

3. **语音识别**：调用第三方 ASR API（SiliconFlow / 阿里云）
   ```java
   // 短视频：一次性识别
   String transcriptText = asrUtils.recognize(audioPath.toFile());
   
   // 长视频：分段识别后合并
   List<TranscriptSegment> segments = new ArrayList<>();
   for (SegmentJob job : segmentJobs) {
       String text = asrUtils.recognize(job.audioFile());
       segments.add(new TranscriptSegment(job.startMs(), job.endMs(), text));
   }
   ```

4. **返回结构**：
   ```java
   record TranscriptSegment(long startMs, long endMs, String text) {}
   ```

---

#### 阶段 3：OCR 分支（关键帧文字提取）

**执行服务**：`VideoContextService.extractKeyFrames()`

**处理流程**：

##### 3.1 关键帧提取（智能筛选）
```bash
# VideoContextService.java:211-216
ffmpeg -i <视频路径> \
  -vf "select=eq(n\,0)+gt(scene\,0.35)+gte(t-prev_selected_t\,30),showinfo" \
  -vsync vfr \
  <输出目录>/frame_%06d.jpg
```

**筛选规则**：
- `eq(n\,0)`：第一帧必选
- `gt(scene\,0.35)`：场景变化度 > 0.35（镜头切换）
- `gte(t-prev_selected_t\,30)`：距上一帧 ≥ 30 秒（避免过密）

##### 3.2 图像去重（感知哈希）
```java
// VideoContextService.java:226-229
long imageHash = differenceHash(frameFiles.get(i).toFile());
if (previousHash != null && Long.bitCount(previousHash ^ imageHash) <= 5) {
    continue; // 汉明距离 ≤ 5，判定为重复帧
}
```

**算法**：Difference Hash（dHash）
- 缩放至 9×8 灰度图
- 比较相邻像素亮度差异
- 生成 64 位哈希值
- 汉明距离 ≤ 5 认为相似

##### 3.3 OCR 文字识别
```java
// VideoContextService.java:234-236
telemetry.increment(traceId, "ocrCalls", 1);
ocrText = ocrUtils.recognize(frameFiles.get(i).toFile());
```

**调用链**：`OcrUtils` → 百度 OCR / 阿里云 OCR → 返回文字

##### 3.4 证据帧上传
```java
// VideoContextService.java:245-256
frameUrl = minioUtils.uploadLocalFile(
        frameFiles.get(i).toFile(),
        frameFiles.get(i).getFileName().toString(),
        EVIDENCE_OBJECT_PREFIX); // "evidence-frames" 前缀
uploadedEvidenceFrames.add(frameUrl);
```

**存储路径**：`minio://media/evidence-frames/<UUID>.jpg`

##### 3.5 返回结构
```java
record FramePart(long timestampMs, String ocrText, String frameName) {}
```

---

#### 阶段 4：结果合并（时间窗口聚合）

```java
// VideoContextService.java:265-279
private List<VideoContext.VideoSegment> merge(
        List<TranscriptSegment> transcripts, 
        List<FramePart> frames) {
    
    Map<Long, SegmentBuilder> windows = new TreeMap<>();
    
    // 按 60 秒窗口聚合 ASR
    for (TranscriptSegment transcript : transcripts) {
        long windowStart = windowStart(transcript.startMs()); // startMs / 60000 * 60000
        windows.computeIfAbsent(windowStart, SegmentBuilder::new)
               .transcripts.add(transcript.text());
    }
    
    // 按 60 秒窗口聚合 OCR
    for (FramePart frame : frames) {
        long windowStart = windowStart(frame.timestampMs());
        SegmentBuilder segment = windows.computeIfAbsent(windowStart, SegmentBuilder::new);
        if (frame.ocrText() != null && !frame.ocrText().isBlank()) {
            segment.ocrTexts.add(frame.ocrText());
        }
        segment.evidenceFrames.add(frame.frameName());
    }
    
    return windows.values().stream().map(SegmentBuilder::build).toList();
}
```

**最终结构**：
```java
record VideoContext(String source, String userGoal, List<VideoSegment> segments) {}
record VideoSegment(
    long startMs,           // 窗口起始时间（0, 60000, 120000...）
    long endMs,             // 窗口结束时间
    String transcript,      // 该窗口内所有 ASR 文本拼接
    List<String> ocrTexts,  // 该窗口内所有 OCR 文本
    List<String> evidenceFrames  // 该窗口内所有关键帧 URL
) {}
```

---

### 2.4 容错与兜底

#### 单路失败兜底
```java
// VideoContextService.java:145-160
if (transcriptResult.failed() && frameResult.failed()) {
    throw new IllegalStateException("ASR 和 OCR 分支均失败");
}
if (transcriptResult.failed()) {
    telemetry.increment(traceId, "asrBranchFailures", 1);
    log.warn("video_context_asr_branch_failed", transcriptResult.error());
}
if (frameResult.failed()) {
    telemetry.increment(traceId, "ocrBranchFailures", 1);
    deleteEvidenceFrames(uploadedEvidenceFrames); // 清理已上传的证据帧
}
```

#### 超时取消机制
```java
// VideoContextService.java:90-105
try {
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(60);
    BranchResult<TranscriptSegment> transcriptResult = awaitBranch(transcriptFuture, deadline);
    BranchResult<FramePart> frameResult = awaitBranch(frameFuture, deadline);
} catch (TimeoutException e) {
    cleanupWorkDir = cancelBranches(branchesFinished, transcriptFuture, frameFuture);
    throw new IllegalStateException("VideoContext 分支处理超过总时间预算", e);
}
```

---

## 三、AI Agent 分析链路（多轮闭环）

### 3.1 入口与编排

**核心服务**：`AgentLoopService.run()`

**三阶段 Agent 模型**：
1. **Planner** — 拆解用户目标为子任务
2. **Executor** — 按计划生成结构化分析结果
3. **Critic** — 校验结果，判断是否需要重跑

---

### 3.2 阶段 1：Planner（任务拆解）

#### 输入
```java
// AgentLoopService.java:154-166
VideoContext relevantContext = longVideoContextService.selectRelevant(mediaId, context);
AgentState.AgentPlan plan = deepSeekUtils.plan(context, planInstruction(profile));
```

#### Planner Prompt 结构
```text
你是一个视频内容分析的任务规划师。用户目标：{userGoal}

视频上下文摘要：
[00:00-01:00] ASR: {transcript} | OCR: {ocrTexts}
[01:00-02:00] ASR: {transcript} | OCR: {ocrTexts}
...

请将用户目标拆解为 2-5 个具体子任务，返回 JSON：
{
  "understoodGoal": "对用户目标的理解",
  "tasks": ["子任务1", "子任务2", ...]
}
```

#### 输出验证
```java
// AgentLoopService.java:250-257
private boolean isPlanValid(AgentState.AgentPlan plan) {
    return plan != null 
        && plan.understoodGoal() != null && !plan.understoodGoal().isBlank()
        && plan.tasks() != null && !plan.tasks().isEmpty()
        && plan.tasks().size() <= MAX_PLAN_TASKS  // 最多 5 个任务
        && plan.tasks().stream().noneMatch(
            task -> task == null || task.isBlank() || task.length() > 500);
}
```

#### Checkpoint 持久化
```java
// AgentLoopService.java:174
checkpointService.savePlan(mediaId, context.userGoal(), modeOf(profile), plan);
```
- **Redis 键**：`checkpoint:plan:{mediaId}:{goalDigest}:{mode}`
- **作用**：MQ 重投时直接复用，避免重复调用 LLM

---

### 3.2 阶段 2：Executor（生成结构化结果）

#### 输入
```java
// AgentLoopService.java:188
AnalysisResult result = deepSeekUtils.execute(
    context, plan, previousCritique, executeInstruction(profile));
```

#### Executor Prompt 结构
```text
你是视频内容分析执行器。根据以下任务计划生成结构化分析报告：

任务计划：
1. {task1}
2. {task2}
...

视频完整上下文：
[00:00-01:00] ASR: {transcript} | OCR: {ocrTexts} | 证据帧: {frameUrls}
[01:00-02:00] ...

{如果有 previousCritique，追加：}
上一轮 Critic 反馈：
- 缺失要求：{missingRequirements}
- 无证据支撑的结论：{unsupportedClaims}
- 需补充的时间戳：{requiredTimestamps}

请生成 JSON 格式结果：
{
  "title": "报告标题",
  "sections": [
    {"key": "段落标识", "items": ["要点1", "要点2"]},
    ...
  ],
  "conclusions": ["核心结论1", "核心结论2", ...],
  "evidence": [
    {"timestampMs": 12000, "text": "证据原文", "type": "ASR/OCR"},
    ...
  ]
}
```

#### 模式自定义（ModeProfile）
```java
// 不同模式追加不同指令
- GENERAL：空指令（默认行为）
- LEARNING：追加 "生成知识点清单 + 难度分级"
- REVIEW：追加 "生成优缺点对比 + 改进建议"
- CREATION：追加 "提取创作灵感 + 脚本结构"
```

#### 草稿持久化
```java
// AgentLoopService.java:190-192
AgentState draft = new AgentState(context.userGoal(), plan, result, null, round);
checkpointService.saveExecutionState(mediaId, draft, modeOf(profile));
```
- **Redis 键**：`checkpoint:execution:{mediaId}:{goalDigest}:{mode}`
- **作用**：Critic 前持久化，避免 Critic 失败后重新生成整份产物

---

### 3.3 阶段 3：Critic（证据校验）

#### 输入
```java
// AgentLoopService.java:207-208
AgentState.CriticResult critique = normalizeCritique(
    deepSeekUtils.critique(context, plan, result, criticInstruction(profile)));
```

#### Critic Prompt 结构
```text
你是视频分析结果的质量校验器。请核验以下产物是否满足要求：

任务计划：
{plan.tasks}

生成的结果：
{result.title}
段落：{result.sections}
结论：{result.conclusions}
证据：{result.evidence}

原始视频上下文（用于证据核验）：
[00:00-01:00] ASR: {transcript} | OCR: {ocrTexts}
...

校验维度：
1. 目标覆盖：所有任务是否都有对应内容？
2. 结构完整：title、sections、conclusions、evidence 是否齐全？
3. 证据绑定：每条结论是否有时间戳证据支撑？
4. 证据真实：所有时间戳是否真实存在于 ASR/OCR 中？

返回 JSON：
{
  "passed": true/false,
  "feedback": ["改进建议1", "改进建议2"],
  "missingRequirements": ["缺失的任务要求"],
  "unsupportedClaims": ["无证据支撑的结论"],
  "requiredTimestamps": [12000, 34000]  // 需补充的时间戳
}
```

#### 后处理增强

##### 1. 结构边界强制
```java
// AgentLoopService.java:340-365
private AgentState.CriticResult enforceStructureBounds(
        AnalysisResult result, 
        AgentState.CriticResult critique,
        ModeProfile profile) {
    
    List<String> feedback = new ArrayList<>(critique.feedback());
    
    if (result.title() == null || result.title().isBlank()) {
        feedback.add("补充明确的产物标题");
    }
    if (result.conclusions() == null || result.conclusions().isEmpty()) {
        feedback.add("补充覆盖 Planner 任务的核心结论");
    }
    if (result.evidence() == null || result.evidence().isEmpty()) {
        feedback.add("为核心结论补充带时间戳的 ASR 或 OCR 证据");
    }
    
    // 模式特定段落检查
    List<String> missingSections = missingSectionKeys(result, profile);
    if (!missingSections.isEmpty()) {
        feedback.add("补充当前分析模式要求的结构化段落: " + String.join(", ", missingSections));
    }
    
    return new AgentState.CriticResult(false, feedback, ...);
}
```

##### 2. 证据边界强制（代码验证）
```java
// AgentLoopService.java:283-338
private AgentState.CriticResult enforceEvidenceBounds(
        VideoContext context,
        AnalysisResult result,
        AgentState.CriticResult critique) {
    
    // 验证每条证据的时间戳是否在 ASR/OCR 中存在
    List<AnalysisResult.Evidence> invalidEvidence = result.evidence().stream()
        .filter(evidence -> !evidenceVerificationService.supported(context, evidence))
        .toList();
    
    // 验证每条结论是否有证据绑定
    List<String> unsupportedClaims = result.conclusions().stream()
        .filter(claim -> result.evidence().stream().noneMatch(
            evidence -> evidenceVerificationService.supportsClaim(context, claim, evidence)))
        .toList();
    
    if (!invalidEvidence.isEmpty() || !unsupportedClaims.isEmpty()) {
        // 追加反馈
        feedback.add("为每条结论重新检索并绑定可核验的时间戳证据");
        
        // 标记无效证据的时间戳需要重新检索
        invalidEvidence.stream()
            .map(AnalysisResult.Evidence::timestampMs)
            .forEach(requiredTimestamps::add);
    }
    
    return new AgentState.CriticResult(false, feedback, ...);
}
```

**证据验证逻辑**：
```java
// EvidenceVerificationService.supported()
boolean supported(VideoContext context, AnalysisResult.Evidence evidence) {
    long timestampMs = evidence.timestampMs();
    String evidenceText = evidence.text();
    
    // 查找该时间戳所在的 60 秒窗口
    VideoContext.VideoSegment segment = context.segments().stream()
        .filter(seg -> timestampMs >= seg.startMs() && timestampMs < seg.endMs())
        .findFirst()
        .orElse(null);
    
    if (segment == null) return false;
    
    // 检查证据文本是否在 ASR 或 OCR 中出现
    boolean inASR = segment.transcript().contains(evidenceText);
    boolean inOCR = segment.ocrTexts().stream().anyMatch(ocr -> ocr.contains(evidenceText));
    
    return inASR || inOCR;
}
```

---

### 3.4 多轮迭代机制

#### 迭代条件判断
```java
// AgentLoopService.java:138-148
for (int round = state.round() + 1; round <= maxRounds; round++) {
    state = executeRound(mediaId, relevantContext, plan, state.critique(), round, runStartedNanos, profile);
    
    if (state.critique().passed()) break;  // 通过则结束
    
    if (round < maxRounds) {
        // 未通过且还有预算 → 补充证据 + 修订计划
        relevantContext = contextForRetry(mediaId, context, relevantContext, state.critique(), profile);
        plan = revisePlanForRetry(mediaId, relevantContext, plan, state.critique(), profile);
    }
}
```

#### 证据补充策略
```java
// AgentLoopService.java:367-382
private VideoContext contextForRetry(
        Long mediaId,
        VideoContext fullContext,
        VideoContext selectedContext,
        AgentState.CriticResult critique,
        ModeProfile profile) {
    
    if (!requiresEvidenceRefresh(critique)) {
        // 只是目标覆盖问题 → 仅重写，不补充证据
        telemetry.incrementCurrent("criticRewriteOnlyRetries", 1);
        return selectedContext;
    }
    
    // 需要补充证据 → 按 Critic 指定的时间戳定向检索
    telemetry.incrementCurrent("criticEvidenceRefreshes", 1);
    VideoContext refined = longVideoContextService.refineForCritique(
            mediaId, fullContext, selectedContext, critique);
    
    return refined;
}
```

**`refineForCritique` 实现**：
```java
// LongVideoContextService.refineForCritique()
VideoContext refineForCritique(
        Long mediaId,
        VideoContext fullContext,
        VideoContext currentContext,
        AgentState.CriticResult critique) {
    
    List<Long> requiredTimestamps = critique.requiredTimestamps();
    
    // 从完整上下文中提取 Critic 要求的时间戳片段
    List<VideoContext.VideoSegment> additionalSegments = fullContext.segments().stream()
        .filter(segment -> requiredTimestamps.stream().anyMatch(
            ts -> ts >= segment.startMs() && ts < segment.endMs()))
        .toList();
    
    // 合并现有上下文 + 补充片段
    List<VideoContext.VideoSegment> merged = Stream.concat(
            currentContext.segments().stream(),
            additionalSegments.stream())
        .distinct()
        .sorted(Comparator.comparingLong(VideoContext.VideoSegment::startMs))
        .toList();
    
    return new VideoContext(currentContext.source(), currentContext.userGoal(), merged);
}
```

#### 计划修订策略
```java
// AgentLoopService.java:391-415
private AgentState.AgentPlan revisePlanForRetry(
        Long mediaId,
        VideoContext context,
        AgentState.AgentPlan currentPlan,
        AgentState.CriticResult critique,
        ModeProfile profile) {
    
    if (critique.missingRequirements().isEmpty()) {
        return currentPlan;  // 无缺失要求 → 不修订计划
    }
    
    try {
        // 调用 LLM 补充缺失任务
        AgentState.AgentPlan revisedPlan = deepSeekUtils.replan(
                context, currentPlan, critique, planInstruction(profile));
        
        validatePlan(revisedPlan);
        telemetry.incrementCurrent("planRevisions", 1);
        
        checkpointService.savePlan(mediaId, context.userGoal(), modeOf(profile), revisedPlan);
        return revisedPlan;
        
    } catch (RuntimeException e) {
        // LLM 修订失败 → 降级保留原计划
        telemetry.incrementCurrent("planRevisionFallbacks", 1);
        log.warn("agent_replan_failed, fallback to current plan", e);
        return currentPlan;
    }
}
```

---

### 3.5 预算控制与终止

#### 预算维度
```java
// AgentLoopService.java:43-59
@Value("${agent.budget.max-rounds:2}") int maxRounds;
@Value("${agent.budget.max-duration-ms:120000}") long maxDurationMs;
@Value("${agent.budget.max-estimated-tokens:50000}") long maxEstimatedTokens;
@Value("${agent.budget.max-estimated-cost:0}") double maxEstimatedCost;
```

#### 检查点
```java
// AgentLoopService.java:445-460
private void checkBudget(long startedNanos, String completedStage) {
    AgentExecutionBudget.check(completedStage);
    long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
    AgentTelemetry.BudgetUsage usage = telemetry.currentUsage();
    
    String reason = null;
    if (elapsedMs > maxDurationMs) {
        reason = "Agent 超过最大执行时长 " + maxDurationMs + "ms";
    } else if (usage.estimatedTokens() > maxEstimatedTokens) {
        reason = "Agent 超过最大 Token 预算 " + maxEstimatedTokens;
    } else if (maxEstimatedCost > 0 && usage.estimatedCost() > maxEstimatedCost) {
        reason = "Agent 超过最大成本预算 " + maxEstimatedCost;
    }
    
    if (reason != null) {
        telemetry.incrementCurrent("budgetTerminations", 1);
        throw new BudgetExceededException(completedStage + " 后终止：" + reason);
    }
}
```

#### 调用时机
- Planner 完成后
- 每轮 Executor 完成后
- 每轮 Critic 完成后

---

## 四、完整流程串联图

```
                    ┌─────────────────────┐
                    │  用户提交分析请求   │
                    │ POST /analysis/ai   │
                    └──────────┬──────────┘
                               │
                    ┌──────────▼────────────┐
                    │ AnalysisController    │
                    │ - 参数校验            │
                    │ - Checkpoint 复用检查 │
                    └──────────┬────────────┘
                               │
                    ┌──────────▼───────────────┐
                    │ AnalysisDispatchService  │
                    │ - 限流（用户级+全局级）  │
                    │ - 幂等键（6小时TTL）     │
                    │ - MQ 投递               │
                    └──────────┬───────────────┘
                               │
                    ┌──────────▼──────────────┐
                    │ RocketMQ (异步解耦)      │
                    │ Topic: video-analysis   │
                    └──────────┬──────────────┘
                               │
                    ┌──────────▼─────────────┐
                    │ VideoAnalysisConsumer  │
                    │ - 内容级分布式锁       │
                    │ - 结果复用（3级检查）  │
                    │ - 重试控制（最多3次）  │
                    └──────────┬─────────────┘
                               │
        ┌──────────────────────┴───────────────────────┐
        │                                               │
┌───────▼────────┐                            ┌────────▼────────┐
│   AiService    │                            │  已有结果复用    │
│ asyncAnalyze() │                            │ - Checkpoint    │
└───────┬────────┘                            │ - 内容级归属    │
        │                                     └─────────────────┘
        │
┌───────▼─────────────────────────────────────────────────┐
│          VideoContextService.build()                    │
│          （视频信息提取 - 并行分支）                     │
└────────┬─────────────────────────┬────────────────────┘
         │                         │
┌────────▼──────────┐     ┌───────▼──────────┐
│  ASR 分支          │     │  OCR 分支         │
│ (asrExecutor)     │     │ (ocrExecutor)    │
├───────────────────┤     ├──────────────────┤
│ 1. FFmpeg 提取音频│     │ 1. FFmpeg 关键帧 │
│ 2. 检测时长       │     │ 2. dHash 去重    │
│ 3. 分段/一次识别  │     │ 3. OCR 识别      │
│ 4. ASR API 调用   │     │ 4. MinIO 上传    │
└────────┬──────────┘     └───────┬──────────┘
         │                        │
         └────────┬───────────────┘
                  │
         ┌────────▼──────────┐
         │ 时间窗口合并       │
         │ (60秒/窗口)       │
         │ VideoContext      │
         └────────┬──────────┘
                  │
         ┌────────▼───────────────────────────────┐
         │      AgentLoopService.run()            │
         │      （多轮闭环 Agent 编排）            │
         └────────┬───────────────────────────────┘
                  │
         ┌────────▼────────────────────────────────┐
         │ 阶段 1: Planner (任务拆解)               │
         │ - LLM 拆解用户目标为 2-5 个子任务        │
         │ - Checkpoint 持久化                     │
         │ - 预算检查                              │
         └────────┬────────────────────────────────┘
                  │
         ┌────────▼────────────────────────────────┐
         │ 阶段 2: Executor (生成结构化结果)        │
         │ - 按计划生成 title/sections/conclusions │
         │ - 绑定时间戳证据                        │
         │ - 草稿持久化                            │
         │ - 预算检查                              │
         └────────┬────────────────────────────────┘
                  │
         ┌────────▼────────────────────────────────┐
         │ 阶段 3: Critic (质量校验)                │
         │ - LLM 校验目标覆盖 + 结构完整 + 证据绑定│
         │ - 代码强制验证时间戳真实性               │
         │ - 生成反馈（feedback/missing/unsupported）│
         │ - 预算检查                              │
         └────────┬────────────────────────────────┘
                  │
         ┌────────▼─────────┐
         │  passed = true?  │
         └────────┬─────────┘
                  │
        ┌─────────┴──────────┐
        │ YES               │ NO (且 round < maxRounds)
        │                   │
┌───────▼────────┐  ┌──────▼─────────────────────────┐
│ 保存最终结果    │  │ 证据补充 + 计划修订             │
│ SSE 推送完成   │  │ - refineForCritique() 定向检索 │
│ 落库 MediaFile │  │ - replan() LLM 补充任务         │
└────────────────┘  │ - 返回 Executor 重新生成        │
                    └────────────────────────────────┘
```

---

## 五、关键设计亮点

### 5.1 内容级复用（降本增效）
- **三级缓存**：本地 Checkpoint → Redis 归属索引 → 内容锁等待
- **节省成本**：同一视频重复上传无需重新 ASR/OCR/LLM
- **分布式协调**：Redisson 锁确保同一内容只处理一次

### 5.2 并行容错架构
- **ASR 和 OCR 独立线程池**：互不阻塞
- **单路失败不影响整体**：有一路成功即可继续
- **超时取消机制**：60 分钟总预算，超时自动清理

### 5.3 Agent 闭环反馈
- **Planner → Executor → Critic → Planner**：自我修正
- **证据强制验证**：代码层二次校验，防止 LLM 幻觉
- **定向证据补充**：按 Critic 反馈精准检索，避免全量重传

### 5.4 Checkpoint 断点续传
- **每阶段持久化**：Plan / Execution / Critic 独立保存
- **MQ 重试友好**：重投时直接跳到上次失败点
- **减少重复计算**：Plan 复用率高，LLM 调用次数大幅降低

### 5.5 多维预算控制
- **轮次限制**：最多 2 轮（默认）
- **时长限制**：120 秒（默认）
- **Token 限制**：50000 tokens（默认）
- **成本限制**：可配置美元金额上限

---

## 六、对比 VideoCourseAI 的差距

| 维度 | DOVideo-AI | VideoCourseAI |
|------|-----------|--------------|
| **视频提取** | ASR + OCR 并行，智能关键帧筛选 | 仅 ASR，无 OCR |
| **AI 分析** | 三阶段 Agent（Planner/Executor/Critic） | 单次 LLM 调用 |
| **证据绑定** | 时间戳精准绑定 + 代码验证 | 无证据机制 |
| **多轮迭代** | 最多 2 轮自我修正 | 无迭代 |
| **结果复用** | 三级缓存（Checkpoint/Redis/锁） | 无复用 |
| **容错能力** | 单路失败可继续，分布式锁防重 | 失败即终止 |
| **实时推送** | SSE 推送各阶段进度 | 轮询查询状态 |
| **预算控制** | 4 维度预算（轮次/时长/Token/成本） | 无预算控制 |

---

## 七、改造建议

若要将 VideoCourseAI 升级为 DOVideo 级别，需要：

1. **引入 OCR 能力**：集成百度/阿里云 OCR API
2. **实现 Agent 闭环**：增加 Planner/Executor/Critic 三阶段编排
3. **增强证据机制**：
   - 时间戳精准绑定
   - `EvidenceVerificationService` 代码验证
4. **Checkpoint 系统**：
   - Redis 多阶段持久化
   - MQ 重试断点续传
5. **内容级复用**：
   - 归属索引（`contentHash → mediaId`）
   - 分布式锁防重复提取
6. **预算控制**：
   - 配置化轮次/时长/Token 限制
   - `AgentExecutionBudget` 中间件
7. **SSE 推送**：
   - 已在 `POLLING_TO_SSE_PLAN.md` 中详细说明
