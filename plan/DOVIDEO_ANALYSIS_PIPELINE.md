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
- **Redis 键**：`analysis:context-owner:{contentHash}`（`AnalysisTaskKeys.contextOwner()`）→ 归属 mediaId
- **有效期**：需结合归属键实际写入逻辑确认（本文未独立核实该 TTL 数字）
- **适用场景**：多用户上传同一视频，或同一用户重复上传
- **兜底**：`AnalysisTaskKeys.normalizeContentHash()` 在 `mediaService.contentHash()` 返回值不是合法 32 位十六进制 MD5 时，会退化为 `"media-" + mediaId`。此时 contentHash 与 mediaId 一一绑定，不会产生真正的内容级复用命中

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
1. **固定按 60 秒切片**：不做时长判断，一律用 FFmpeg `segment` 滤镜强制切片（`SegmentedTranscriptionService.java:82-90`）
   ```java
   new ProcessBuilder(
           "ffmpeg", "-y", "-i", videoPath,
           "-vn", "-acodec", "libmp3lame",
           "-f", "segment", "-segment_time", "60", "-reset_timestamps", "1",
           outputPattern.toString())
   ```
   输出为 `audio_%03d.mp3` 序列文件，每段固定 60 秒（最后一段可能不足 60 秒）。

2. **逐片段调用阿里云 ASR**：按文件名排序后逐个识别，单片失败不影响其他片段
   ```java
   // SegmentedTranscriptionService.java:44-58
   for (int i = 0; i < audioFiles.size(); i++) {
       try {
           String text = aliyunAsrUtils.audioToText(audioFile.toString());
           if (text != null && !text.isBlank()) {
               result.add(new TranscriptSegment(i * SEGMENT_MS, (i + 1) * SEGMENT_MS, text));
           }
       } catch (RuntimeException e) {
           failedSegments++;
           // 记录失败，继续处理下一片段
       }
   }
   // 只有全部片段都失败时才抛异常，单片失败允许整体继续
   ```
   实际 ASR 调用方法是 `AliyunAsrUtils.audioToText(String filePath)`，不是通用的 `asrUtils.recognize(File)`。

3. **返回结构**：`TranscriptSegment` 是独立 DTO（`com.example.server.dto.TranscriptSegment`），不是 `SegmentedTranscriptionService` 内部定义的 record：
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

**调用链**：`OcrUtils` 通过 `ProcessBuilder` 调用本地命令行工具 **Tesseract**（默认命令 `tesseract`，可通过 `tool.ocr.command` 配置），以 `chi_sim+eng` 双语言参数识别图片文字后返回结果，并非调用百度/阿里云等云端 OCR API

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
// AgentLoopService.java:109（selectRelevant）与 164（plan() 调用，位于 resolvePlan 内部）
VideoContext relevantContext = longVideoContextService.selectRelevant(mediaId, context);
...
plan = deepSeekUtils.plan(context, planInstruction(profile));
```
注：`selectRelevant` 和 `plan()` 调用并不在同一处代码块，前者在 `runWithinBudget` 方法第109行，后者在 `resolvePlan` 方法第164行。

#### Planner Prompt 结构
实际 Prompt（`DeepSeekUtils.plan()`）并非人工格式化的时间轴文本，而是把整个 `VideoContext` 对象直接序列化为 JSON 拼接进去：
```java
// DeepSeekUtils.java:98-109
你是 Video Agent 的 Planner。理解用户目标，并拆成 1 到 5 个可执行任务。
任务必须能够仅依靠 VideoContext 中的 ASR、OCR 和时间戳证据完成。
任务按执行顺序排列，每项只描述一个可验证的分析动作。
只返回 JSON：
{
  "understoodGoal": "对用户目标的明确理解",
  "tasks": ["任务1", "任务2", "任务3"]
}
VideoContext:
{ /* objectMapper.writeValueAsString(context) 序列化的完整 VideoContext JSON */ }
```
- 任务数量下限是 **1**，不是 2（即"1 到 5 个"，非"2-5 个"）。
- 模式相关的额外拆解要求通过 `modeSuffix()` 追加在末尾，GENERAL 模式下为空串。

#### 输出验证
```java
// AgentLoopService.java:251-257
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
- **Redis 键格式**：实际前缀是 `agent:checkpoint:`，不是 `checkpoint:`。`AgentCheckpointService` 中真实的 key 拼接逻辑（`AgentCheckpointService.java:291-321`）：
  ```java
  private String checkpointKey(Long mediaId) { return "agent:checkpoint:" + mediaId; }
  private String goalKey(Long mediaId, String goal, AnalysisMode mode) {
      return checkpointKey(mediaId) + ":goal:" + AnalysisTaskKeys.goalDigest(goal, mode);
  }
  ```
  即最终 key 形如 `agent:checkpoint:{mediaId}:goal:{goalDigest}`，`mode` 并非明文拼接的独立段，而是被编码进 `goalDigest`（`AnalysisTaskKeys.goalDigest(goal, mode)` 对模式名和目标文本一起做 SHA-256）。`"plan"`/`"criticState"` 等字段名是传给 `AgentCheckpointRepository` 的字段标识符，而不是 Redis key 的一部分。
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
实际 Prompt（`DeepSeekUtils.execute()`，`DeepSeekUtils.java:260-286`）也是把 Plan/PreviousCritique/VideoContext 直接序列化为 JSON 拼接，输出字段名与文档此前版本不同：
```text
你是 Video Agent 的 Executor。按照计划分析 VideoContext 并生成结构化产物。
逐项执行 Plan 中的任务，最终产物必须覆盖全部任务。
conclusions 中的每条结论都必须至少绑定一条真实证据。
evidence.claim 必须原样复制它所支持的 conclusion，timestampMs 必须落在原始片段内，source 只能是 ASR、OCR 或 ASR+OCR。
不得使用视频上下文之外的事实。
如果存在 Critic 反馈，只修正被指出的问题，并保留已经核验通过的结论和证据。

只返回 JSON：
{
  "title": "产物标题",
  "conclusions": ["结论"],
  "evidence": [
    {"timestampMs": 120000, "source": "ASR", "content": "原始证据内容", "claim": "结论"}
  ],
  "suggestions": ["建议"]
}

Plan: {...}
PreviousCritique: {...}
VideoContext: {...}
```
关键差异：
- 证据字段名是 **`content`/`source`/`claim`**，不是 `text`/`type`。`claim` 字段用于把该条证据绑定到它所支持的具体结论（`conclusion` 原文）。
- 顶层还有一个 **`suggestions`** 字段（改进建议），文档此前的版本没有提到。
- `sections` **不是** GENERAL 模式下的默认字段，只有在模式指令非空时（见下）才会追加要求。

#### 模式自定义（ModeProfile / executeSuffix）
```java
// DeepSeekUtils.java:357-363，executeSuffix()
- GENERAL：modeInstruction 为空串，不追加任何内容，产物结构与上面通用 JSON 完全一致
- 非 GENERAL 模式：追加"本次分析模式的额外产物要求：{modeInstruction}"，
  并要求额外输出一个 "sections" 数组：
  {"key": "英文标识", "title": "面向用户的标题", "items": ["要点"]}
  同时仍需保留 title/conclusions/evidence/suggestions
```
各模式具体的 `planInstruction`/`executeInstruction`/`criticInstruction` 文本定义在 `service/mode/ModeProfile` 及其实现类中，本文未展开核实其具体文案。

#### 草稿持久化
```java
// AgentLoopService.java:189-191
AgentState draft = new AgentState(context.userGoal(), plan, result, null, round);
checkpointService.saveExecutionState(mediaId, draft, modeOf(profile));
```
- **Redis 键**：`saveExecutionState` 实际写入的字段是 **`criticState`**（`AgentCheckpointService.java:165-172`），最终 key 同样是 `agent:checkpoint:{mediaId}:goal:{goalDigest}` 结构，草稿状态被标记为阶段 `EXECUTOR_COMPLETED` 存入这个字段位置，并不存在独立的 `checkpoint:execution:...` 格式。
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
实际 Prompt（`DeepSeekUtils.critique()`，`DeepSeekUtils.java:305-336`）：
```text
你是 Video Agent 的 Critic，只负责检查，不负责改写产物。
检查标准：
1. 是否覆盖用户目标和 Planner 的全部任务；
2. conclusions 中的每条结论是否都有 evidence.claim 的明确绑定；
3. 每条绑定证据的时间戳、来源和原文是否能在 VideoContext 中核验；
4. 是否存在上下文不支持的结论；
5. title、conclusions、evidence、suggestions 是否完整。

只有全部满足时 passed 才能为 true。
feedback 只填写能够基于当前 VideoContext 直接重写的修改动作。
missingRequirements 填写未覆盖的用户目标或 Planner 任务。
unsupportedClaims 填写当前 VideoContext 无法支持、需要重新检索证据的结论。
requiredTimestamps 只填写需要定向加载原始证据的时间戳；无需补充证据时返回空数组。
只返回 JSON：
{
  "passed": false,
  "feedback": ["具体修改建议"],
  "missingRequirements": ["遗漏要求"],
  "unsupportedClaims": ["无证据结论"],
  "requiredTimestamps": [120000]
}

Plan: {...}
Draft: {...}
VideoContext: {...}
```
实际是 **5 条**校验标准，不是 4 条：文档此前版本遗漏了"是否存在上下文不支持的结论"这一条；第5条完整性检查的字段是 `title/conclusions/evidence/suggestions`，不含 `sections`（`sections` 只在特定模式下才是必需字段）。

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

**证据验证逻辑**（`EvidenceVerificationService.java:17-25`，实际代码，非伪代码简化版）：
```java
public boolean supported(VideoContext context, AnalysisResult.Evidence evidence) {
    if (context == null || evidence == null || evidence.content().isBlank()) return false;
    String source = evidence.source().toUpperCase(Locale.ROOT);
    if (!source.contains("ASR") && !source.contains("OCR")) return false;

    return context.segments().stream()
            .filter(segment -> containsTimestamp(segment, evidence.timestampMs()))
            .map(segment -> sourceText(segment, source))
            .anyMatch(candidate -> textMatches(evidence.content(), candidate));
}
```
与简单的原文 `contains` 判断相比，实际实现有三点关键差异：
1. **先校验来源字段**：`evidence.source()` 必须包含 "ASR" 或 "OCR"，否则直接判定不支持。
2. **规范化匹配**：`textMatches()` 会先对证据文本和候选文本做 `normalize()`（转小写 + 去除标点符号和空白），再判断包含关系（`EvidenceVerificationService.java:49-61`），不是原始字符串直接 `contains`。
3. **遍历所有匹配时间戳的片段**：用 `anyMatch` 而非只取第一个片段（`findFirst()`），只要任意一个落在该时间戳范围内的片段命中即算支持。

字段名同样是 `evidence.content()`（不是 `evidence.text()`），`evidence.source()`（不是 `evidence.type()`）。此外还有配套的 `supportsClaim()` 方法（`EvidenceVerificationService.java:28-35`），用于校验某条结论文本是否与某条证据的 `claim` 字段规范化后完全一致，且该证据本身通过 `supported()` 校验。

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

**`refineForCritique` 实现**（`LongVideoContextService.java:58-75`，实际逻辑比按时间戳精确截取要复杂得多）：
```java
public VideoContext refineForCritique(Long mediaId,
                                      VideoContext fullContext,
                                      VideoContext selectedContext,
                                      AgentState.CriticResult critique) {
    Map<String, VideoContext.VideoSegment> segments = new LinkedHashMap<>();
    List<Long> requiredTimestamps = critique == null ? List.of() : critique.requiredTimestamps();

    // 1. 按时间戳 + margin 扩展窗口，从完整上下文中提取相邻片段
    fullContext.segments().stream()
            .filter(segment -> requiredTimestamps.stream().anyMatch(timestamp ->
                    nearSegment(timestamp, segment)))
            .forEach(segment -> segments.put(segmentKey(segment), segment));

    // 2. 基于 Critic 反馈文本（feedback/missingRequirements/unsupportedClaims）
    //    做一次语义检索，作为二次补充来源
    String critiqueQuery = critiqueQuery(fullContext.userGoal(), critique);
    VideoContext retryContext = selectRelevant(mediaId,
            new VideoContext(fullContext.source(), critiqueQuery, fullContext.segments()));
    retryContext.segments().forEach(segment -> segments.putIfAbsent(segmentKey(segment), segment));

    // 3. 保留原有已选片段
    selectedContext.segments().forEach(segment -> segments.putIfAbsent(segmentKey(segment), segment));

    // 4. 按字符预算裁剪，返回最终上下文
    return withinBudget(fullContext, new ArrayList<>(segments.values()));
}
```
与"简单按时间戳区间截取片段再去重合并"的描述相比，实际实现包含三处文档此前遗漏的机制：
1. **Margin 扩展窗口**：`nearSegment()`（`LongVideoContextService.java:107-111`）判断时间戳是否落在片段前后各扩展 `Math.max(60_000L, 片段时长)` 的缓冲区内，不是精确的 `startMs <= ts < endMs` 判断，会额外拉取时间戳附近的相邻片段。
2. **二次语义检索**：还会把 Critic 的 `feedback`/`missingRequirements`/`unsupportedClaims` 拼成一段查询文本，调用 `selectRelevant()` 做一次基于语义相关性的检索补充，不只是按时间戳硬性截取。
3. **字符预算裁剪**：最终通过 `withinBudget()`（`MAX_CONTEXT_CHARS = 24_000`）按字符数上限裁剪片段集合，避免上下文无限增长。

去重是用 `LinkedHashMap<String, VideoSegment>` 以 `"startMs:endMs"` 为 key（`segmentKey()`），效果类似 `distinct()` 但保序且按 key 去重，而不是走 `Stream.distinct()`。

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
实际 `checkBudget()` 调用点（`AgentLoopService.java`）：
- 第111行：Planner 完成后
- 第129行：从 Critic checkpoint 恢复执行时（MQ 重投场景下的"Executor Checkpoint"检查点）
- 第139行：每轮循环**开始前**（"Agent Round N"），不是"Critic 完成后"
- 第195行：Executor 完成后

`critiqueRound()` 方法内部**没有**单独调用 `checkBudget`，即"每轮 Critic 完成后检查预算"的说法在源码中找不到对应调用点，实际预算检查落在轮次开始前和 Executor 完成后。

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
                    │ Topic: video-analysis-  │
                    │        topic（默认值）  │
                    └──────────┬──────────────┘
                               │
                    ┌──────────▼─────────────┐
                    │ VideoAnalysisConsumer  │
                    │ - 任务级锁（内容+目标） │
                    │ - 完成结果复用（1层）  │
                    │ - 重试控制（最多3次）  │
                    └──────────┬─────────────┘
                               │
        ┌──────────────────────┴───────────────────────┐
        │                                               │
┌───────▼────────┐                            ┌────────▼────────┐
│   AiService    │                            │ completedKey 命中│
│ asyncAnalyze() │                            │ 直接复用返回     │
│ (内含三级复用  │                            │（Consumer 层，  │
│  检查，见下）  │                            │ 单层判断）       │
└───────┬────────┘                            └─────────────────┘
        │
┌───────▼─────────────────────────────────────────────────┐
│          VideoContextService.build()                    │
│          （视频信息提取 - 并行分支）                     │
└────────┬─────────────────────────┬────────────────────┘
         │                         │
┌────────▼──────────┐     ┌────────▼──────────┐
│  ASR 分支          │     │  OCR 分支          │
│ (asrExecutor)     │     │ (ocrExecutor)     │
├───────────────────┤     ├───────────────────┤
│ 1. FFmpeg 固定60秒 │     │ 1. FFmpeg 关键帧   │
│    分段切片        │     │ 2. dHash 去重      │
│ 2. 逐段阿里云ASR   │     │ 3. 本地Tesseract   │
│    (单段失败不中断)│     │    OCR识别         │
│                   │     │ 4. MinIO 上传      │
└────────┬──────────┘     └────────┬──────────┘
         │                         │
         └────────┬────────────────┘
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
         │ - LLM 拆解用户目标为 1-5 个子任务        │
         │ - Checkpoint 持久化                     │
         │ - 预算检查                              │
         └────────┬────────────────────────────────┘
                  │
         ┌────────▼────────────────────────────────┐
         │ 阶段 2: Executor (生成结构化结果)        │
         │ - 按计划生成 title/conclusions/evidence/ │
         │   suggestions（含sections需模式指定）    │
         │ - 绑定时间戳证据（source/content/claim） │
         │ - 草稿持久化                            │
         │ - 预算检查                              │
         └────────┬────────────────────────────────┘
                  │
         ┌────────▼────────────────────────────────┐
         │ 阶段 3: Critic (质量校验)                │
         │ - LLM 校验目标覆盖+结构完整+证据绑定    │
         │   +上下文支持性（共5条标准）            │
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
- **三级检查**（发生在 `AiService`/`VideoContextService` 内部，非 Consumer 层）：本 mediaId 检查点复用 → Redis 内容归属索引（`analysis:context-owner:{contentHash}`）→ 内容级锁等待（`contextLock`，5分钟）
- **节省成本**：同一视频重复上传无需重新 ASR/OCR/LLM
- **分布式协调**：Redisson 锁确保同一内容只处理一次
- 注意与 `VideoAnalysisConsumer` 自身的"内容+目标摘要"任务锁（`AnalysisTaskKeys.lock()`，非阻塞 `tryLock()`）和完成结果复用（`completedKey`，7天TTL）区分，两者是不同层级、不同粒度的机制

### 5.2 并行容错架构
- **ASR 和 OCR 独立线程池**：互不阻塞
- **单路失败不影响整体**：有一路成功即可继续
- **超时取消机制**：60 分钟总预算，超时自动清理

### 5.3 Agent 闭环反馈
- **Planner → Executor → Critic → Planner**：自我修正
- **证据强制验证**：代码层二次校验，防止 LLM 幻觉
- **定向证据补充**：按 Critic 反馈精准检索，避免全量重传

### 5.4 Checkpoint 断点续传
- **分阶段持久化**：Plan 独立保存（`plan` 字段）；Executor 草稿与 Critic 校验结果共用 `criticState` 字段（草稿落盘时阶段标记为 `EXECUTOR_COMPLETED`，Critic 完成后更新阶段为 `CRITIC_PASSED`/`CRITIC_RETRY_REQUIRED`）
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

1. **引入 OCR 能力**：DOVideo-AI 实际用的是本地 Tesseract 命令行工具（非云端 OCR API），可视成本/部署环境权衡选择本地 Tesseract 或接入百度/阿里云 OCR API
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
