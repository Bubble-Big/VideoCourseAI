# VideoCourseAI 对齐 DOVideo-AI 改造计划

> 基于 `plan/DOVIDEO_ANALYSIS_PIPELINE.md`（已核实修正版）与当前 VideoCourseAI 源码的差距分析。
> 范围确认（用户已选定）：**引入本地 Tesseract OCR** + **完整 Planner→Executor→Critic 多轮 Agent 闭环**。
> 本文档只做架构设计与任务拆解，不包含代码实现。

---

## 一、现状与目标差距总览

| 维度 | VideoCourseAI 现状 | DOVideo-AI 目标 | 差距定级 |
|------|-------------------|-----------------|---------|
| 音频转写 | `AliyunAsrUtils.audioToText()` 整段一次性识别，无分段 | FFmpeg 固定 60 秒分段切片，逐段识别，单段失败不中断整体 | 中（需改造，非新建） |
| 画面文字 | 无 OCR 能力 | FFmpeg 场景切换关键帧提取 + dHash 去重 + Tesseract OCR | 高（全新能力） |
| 分析产物 | `DeepSeekUtils.analyzeContent()` 单次调用，返回 Markdown 文本存 `summary` 字段 | Planner→Executor→Critic 结构化 JSON（title/conclusions/evidence/suggestions），可含 sections | 高（数据结构级变更） |
| 证据机制 | 无——总结内容与原始 ASR 无绑定关系 | 每条结论绑定时间戳证据，代码层校验证据文本真实存在于 ASR/OCR 原文 | 高（全新能力） |
| 多轮迭代 | 无，一次 LLM 调用定型 | 最多 2 轮 Critic 校验失败可定向补证据 + 重规划重跑 | 高（全新能力） |
| 内容级复用粒度 | 复用"转写全文文本" + "分析 summary 文本"两级（`ContentTaskGate.resolveTranscript`/`resolveAnalysis`） | 复用粒度细化到 VideoContext（ASR+OCR+时间戳片段），以及 Plan/Draft/Critique 各阶段 Checkpoint | 中（现有复用框架可扩展，无需推倒重来） |
| Checkpoint 断点续传 | 无阶段级 Checkpoint，补偿调度器发现卡死后整体重跑 `asyncAnalyze` | Plan/Draft/Critique 各阶段独立落盘，重跑可跳过已完成阶段 | 中 |
| 预算控制 | 无 Token/时长/轮次预算，靠 `@Async` 线程池 + 补偿调度器兜底卡死 | 轮次/时长/Token/成本四维度预算，超限主动终止并标记警告态 | 中 |
| 任务编排骨架 | RocketMQ 触发派发 + `@Async` 执行 + DB 状态机 + 补偿调度器兜底重试（已成熟，**不在改造范围**） | （DOVideo 是 MQ 消费直接同步执行 + MQ 重投，与 VideoCourseAI 的"触发派发+补偿式重试"模式不同） | 保留 VideoCourseAI 现有模式，不迁移 DOVideo 的 MQ 重投模式 |

**结论**：VideoCourseAI 现有的任务编排骨架（`ContentTaskGate` 内容级锁/幂等/复用 + `AiStatus` 状态机 + 补偿调度器 + SSE 推送）已经是一套成熟、经过多轮加固的机制，**不应该被推翻**。这次改造的本质是把 `AiService.asyncAnalyze()` 内部"transcribe → summarize"两步，替换/扩展为"构建 VideoContext（ASR+OCR）→ Agent 闭环（Plan/Execute/Critic）"，尽量在现有骨架内插入新能力，而不是引入 DOVideo 的整套 MQ 重投 + 独立 Checkpoint 体系。

---

## 二、目标架构设计

### 2.1 改造后的整体链路（嵌入现有骨架）

```
DebugController.aiAnalyze()               [不变]
  幂等键 + 限流 + 置 PENDING + 发 MQ
        │
VideoAnalysisConsumer.onMessage()          [不变]
  只做触发派发（@Async 提交后 ACK）
        │
AiService.asyncAnalyze()  [@Async, 内部结构改造]
  contentTaskGate.inAnalysisLock(contentHash, () -> {
        │
        ├─ 1. buildVideoContextWithReuse(mediaFile, contentHash, force)   [新，替换原 transcribeWithReuse]
        │      └─ 内容级锁内：查 VideoContext 归属复用 → 未命中则真正构建
        │           ├─ ASR 分支：FFmpeg 60s 分段 + 逐段识别（改造现有 AliyunAsrUtils 调用方式）
        │           └─ OCR 分支：FFmpeg 关键帧 + dHash 去重 + Tesseract 识别（全新）
        │           └─ 合并为 VideoContext（按 60s 窗口聚合 ASR+OCR+证据帧）
        │
        ├─ 2. AgentLoopService.run(mediaId, videoContext)    [新，替换原 generateSummaryFromText]
        │      内部同步循环（非独立 @Async，仍在当前线程执行完才返回）：
        │        Planner → Executor → Critic → (未通过且轮次未满 → 补证据 + 重规划 → 回到 Executor)
        │      每阶段结束后检查预算（轮次/时长/Token），超限则终止并标记警告态但仍返回可用结果
        │
        └─ 3. 落库 AnalysisResult（结构化）+ 渲染 Markdown 摘要（前端兼容）+ SUCCESS
  })
```

**关键设计原则**：
- 锁的粒度不变：仍是 `contentTaskGate.inAnalysisLock`（内容级），内部两步都在锁内完成，与现状一致。
- 补偿调度器完全不用改：它只认 `PROCESSING` 超时+`ai_status`，不关心 `asyncAnalyze` 内部做了几步。多轮 Agent 循环变长后，只需要**上调卡死阈值**（`AnalysisCompensationScheduler` 的 `thresholdMinutes`）。
- `force=true` 跳过复用逻辑的现有约定保持不变，延伸到 VideoContext 级复用。

### 2.2 视频信息提取层改造（VideoContext 构建）

#### 2.2.1 ASR 分支改造

现有 `AliyunAsrUtils.audioToText(String filePath)` 是整段一次性调用，无分段。改造为：

- 新增音频分段步骤：复用 `FfmpegUtils` 思路，新增分段方法（固定 60 秒，`-f segment -segment_time 60`），产出 `audio_%03d.mp3` 序列。
- 逐段调用现有 `AliyunAsrUtils.audioToText()`（**方法本身不用改**，只是调用方从"整个文件调一次"变成"每个分段调一次"）。
- 单段失败：记录失败计数，跳过该段继续处理其他段（对齐 DOVideo `SegmentedTranscriptionService` 的容错策略），仅当全部分段失败才判定 ASR 分支失败。
- 输出结构从纯字符串改为 `List<TranscriptSegment(startMs, endMs, text)>`，供后续按窗口合并。

**影响范围**：`AliyunDeepSeekStrategy.transcribe()` 的实现方式改变，但 `AiAnalysisStrategy` 接口签名可以保持 `String transcribe(String videoPath)` 不变（内部把分段结果拼接返回，兼容"纯文字提取"功能 `/debug/transcribe`），同时新增一个返回结构化片段的方法供 Agent 链路使用。

#### 2.2.2 OCR 分支（全新）

新增能力，模仿 DOVideo-AI 已验证的设计：

1. **关键帧提取**：FFmpeg `select=eq(n\,0)+gt(scene\,0.35)+gte(t-prev_selected_t\,30)` 场景切换检测，输出候选帧序列。
2. **感知哈希去重**：dHash（9×8 缩放灰度图，汉明距离 ≤ 5 判重复），避免同一 PPT 页面产生大量重复 OCR 调用。
3. **OCR 识别**：本地 Tesseract 命令行工具（`chi_sim+eng` 双语言），复用 DOVideo `OcrUtils` 的 `ProcessBuilder` 调用模式。
4. **证据帧上传**：复用现有 `MinioUtils`，新增 `evidence-frames/` 前缀，上传关键帧原图供前端展示证据来源。

**新增文件**：
- `utils/OcrUtils.java`（Tesseract 调用，仿 DOVideo 实现）
- `utils/FfmpegUtils` 扩展：新增关键帧提取方法（区别于现有的纯音频提取方法）

**部署依赖**：需在部署环境安装 Tesseract 并配置语言包（`chi_sim`/`eng`），新增配置项 `tool.ocr.command`（默认 `tesseract`），与现有 `tool.ytdlp.path`/`tool.ffmpeg.dir` 的配置模式一致，需要写入 `CLAUDE.md`"已知陷阱"章节。

#### 2.2.3 合并为 VideoContext

新增 `VideoContext(source, userGoal, List<VideoSegment>)` 数据结构，`VideoSegment(startMs, endMs, transcript, ocrTexts, evidenceFrames)`，按 60 秒窗口把 ASR 片段与 OCR 片段的时间戳对齐合并（逻辑对齐 DOVideo `VideoContextService.merge()`）。

### 2.3 AI Agent 闭环设计

新增 `AgentLoopService`，内部三阶段，全部通过改造后的 `DeepSeekUtils` 发起结构化 LLM 调用：

| 阶段 | 职责 | 对应现有能力 |
|------|------|-------------|
| Planner | 把用户分析目标拆解为 1-5 个可执行子任务 | 全新，`DeepSeekUtils` 需新增 `plan()` 方法 |
| Executor | 按计划生成结构化产物：`title/conclusions/evidence/suggestions` | 替代现有 `analyzeContent()` 单次总结调用 |
| Critic | 校验目标覆盖、结构完整性、证据绑定（含代码层证据真实性校验） | 全新，`DeepSeekUtils` 需新增 `critique()` 方法 + 新增 `EvidenceVerificationService` |

**DeepSeekUtils 改造要点**（当前 `DeepSeekUtils.java` 是纯 OkHttp 手写 JSON 请求，无结构化输出解析）：
- 现有 `callWithRetry()` 的重试/退避逻辑（3次重试，5xx/408/429 退避）可以直接复用，不用重写。
- 需要新增一层"结构化输出解析 + 解析失败重试一次"的包装（对齐 DOVideo `structuredChat()` 的做法：首次解析失败则追加"请严格返回合法 JSON"提示重试一次）。
- 新增 `plan(VideoContext)`、`execute(VideoContext, plan, previousCritique)`、`critique(VideoContext, plan, result)`、`replan(...)` 四个方法，均走"拼 prompt → callWithRetry → 解析 JSON"的既有模式。

**证据校验**（`EvidenceVerificationService`，全新）：
- 校验证据 `source` 字段含 ASR/OCR。
- 规范化匹配（转小写去标点）判断证据原文是否出现在对应时间窗口的 ASR/OCR 文本中。
- 校验每条结论是否有证据 `claim` 字段绑定。

**多轮迭代**：
- 最多 2 轮（可配置 `agent.budget.max-rounds`），Critic 不通过且轮次未满时：
  - 若只是目标覆盖问题 → 仅重新执行 Executor，不重新检索证据。
  - 若涉及证据缺口（`requiredTimestamps`/`unsupportedClaims`）→ 从完整 VideoContext 中按时间戳±margin 窗口补充相关片段，重新执行。
- 轮次耗尽仍未通过：不判失败，落库为"警告态"结果（保留已生成内容 + 标记未完全校验通过），对齐 DOVideo 的"ANALYSIS_COMPLETED_WITH_WARNINGS"语义。

### 2.4 预算控制

新增轮次/时长/Token 三维度预算（成本维度视 SiliconFlow 计费方式决定是否需要，可先不做）：
- `agent.budget.max-rounds`（默认 2）
- `agent.budget.max-duration-ms`（默认建议 180000，比 DOVideo 的 120000 更宽松，因为整段逻辑在 `@Async` 线程里跑，没有 MQ 消费超时压力，可以给更长时间）
- `agent.budget.max-estimated-tokens`（默认视 SiliconFlow 定价与 VideoContext 平均大小估算，需要在实施阶段实测校准）

超限行为：终止当前轮次循环，用最后一次 Executor 产物落库为警告态，而不是直接判失败——避免用户看到"分析失败"却其实已经生成了可用内容。

### 2.5 内容级复用粒度升级

现状 `ContentTaskGate` 已有两级复用：`resolveTranscript`（转写文本级）+ `resolveAnalysis`（最终 summary 级）。改造后：

- **`resolveTranscript` 升级为 `resolveVideoContext`**：复用对象从"纯文本"变为"VideoContext（含 ASR+OCR+证据帧）"，Redis 归属 key 复用现有 `contextOwner(contentHash)` 语义不变，值从字符串变为序列化的 VideoContext JSON（或单独存表，见第三章）。
- **`resolveAnalysis` 语义不变**：仍是"同内容是否已有完整分析结果"，只是结果内容从纯文本变为结构化 JSON。
- Plan/Draft/Critique 阶段级 Checkpoint（用于同一次分析内部的多轮迭代，不是跨 mediaId 复用）：建议**只落 Redis，不落 DB**，TTL 设置为略长于预算时长上限（比如 30 分钟），因为这些是分析过程中的临时状态，分析完成后即可失效，不需要长期持久化（对齐 DOVideo `AgentCheckpointService` 的 Redis-only 设计，减少 DB 迁移复杂度）。

---

## 三、数据模型变更

### 3.1 新增/变更表结构

**方案取舍**：现有 `media_transcription.transcript_text` 是纯文本；`media_ai_analysis.summary` 是 Markdown 文本，前端用 `marked` 直接渲染。为保持前端兼容 + 支撑证据校验，采取"新增结构化字段，旧字段继续渲染兼容"的演进策略，而不是推翻重来。

**`media_transcription` 表扩展**（或新增子表 `media_video_context`，建议新增子表，因为 OCR 证据帧数据与"转写文本"语义不同，混进同一张表会让字段职责模糊）：

```sql
CREATE TABLE media_video_context (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    media_id BIGINT NOT NULL COMMENT '关联 media_files.id',
    context_json MEDIUMTEXT COMMENT '序列化的 VideoContext（含 ASR+OCR+证据帧 URL 的 segments）',
    ocr_enabled TINYINT(1) DEFAULT 1 COMMENT '本次是否启用了 OCR（供排查/统计用）',
    created_at DATETIME,
    updated_at DATETIME,
    UNIQUE KEY uk_media_id (media_id)
);
```

> 存整段 JSON blob 而非按 segment 拆行，理由：VideoContext 是"一次分析内部的中间产物"，不需要按 segment 做 SQL 查询，序列化存储更简单，且与 DOVideo 的 Checkpoint 存储粒度一致。

**`media_ai_analysis` 表扩展**（新增列，`summary` 保留不删）：

```sql
ALTER TABLE media_ai_analysis
  ADD COLUMN result_json MEDIUMTEXT COMMENT '结构化分析产物（title/conclusions/evidence/suggestions）',
  ADD COLUMN critic_passed TINYINT(1) DEFAULT NULL COMMENT 'Critic 是否完全校验通过，NULL=未走新链路的历史数据',
  ADD COLUMN agent_rounds INT DEFAULT NULL COMMENT '实际执行的 Agent 轮次数';
```

- `summary` 字段：新链路下由后端把 `result_json` 中的 `conclusions`/`evidence`/`suggestions` 拼装渲染成 Markdown 写入，保证前端 `marked` 渲染逻辑完全不用改。
- 旧数据（改造前分析的记录）`result_json`/`critic_passed` 为 NULL，前端/后端凡是读这两个字段的地方都要判空兼容。

### 3.2 Redis Key 设计（新增，遵循现有 `AnalysisTaskKeys` 命名风格）

| Key | 用途 | TTL |
|-----|------|-----|
| `agent:checkpoint:plan:{mediaId}` | 当前分析的 Plan 阶段快照 | 30 分钟 |
| `agent:checkpoint:draft:{mediaId}` | Executor 草稿快照（Critic 校验前） | 30 分钟 |
| `agent:checkpoint:critique:{mediaId}` | 最近一次 Critic 结果 | 30 分钟 |

不引入 mode/goalDigest 维度（DOVideo 支持多模式分析目标，VideoCourseAI 现状是固定分析目标，无需该复杂度）。

---

## 四、任务拆解

按依赖顺序分为 6 个阶段，每阶段建议对应一次编译验证（`/compile-server`）与一次 `/analyze-video` 端到端验证。

### 阶段 1：ASR 分段改造（独立可先行，风险最低）
- [ ] `FfmpegUtils` 新增音频分段方法（60 秒固定切片）
- [ ] `AliyunDeepSeekStrategy.transcribe()` 改为分段调用 + 单段失败容错 + 拼接返回（保持接口签名兼容，`/debug/transcribe` 纯文字提取功能不受影响）
- [ ] 新增 `TranscriptSegment(startMs, endMs, text)` DTO，供后续 VideoContext 使用
- [ ] 验证：现有转写功能回归测试通过，长视频分段后文本拼接结果与整段识别对比无明显质量下降

### 阶段 2：OCR 能力接入（独立可先行）
- [ ] 部署环境安装 Tesseract，确认 `chi_sim`/`eng` 语言包可用，新增 `tool.ocr.command` 配置项
- [ ] 新增 `utils/OcrUtils.java`（本地命令行调用）
- [ ] `FfmpegUtils` 新增关键帧提取方法（场景切换检测）
- [ ] 新增 dHash 图像去重工具方法
- [ ] 验证：单独跑一段含 PPT/字幕的测试视频，人工核对 OCR 识别文字准确性

### 阶段 3：VideoContext 构建与复用
- [ ] 新增 `VideoContext`/`VideoSegment` DTO
- [ ] 新增 `media_video_context` 表（数据库迁移脚本，编号衔接现有 V11 之后为 V12）
- [ ] 改造 `AiService.transcribeWithReuse()` → `buildVideoContextWithReuse()`：合并 ASR+OCR 分支，按 60 秒窗口聚合，复用逻辑对齐现有 `resolveTranscript`/`rememberTranscript` 模式
- [ ] 证据帧上传：`MinioUtils` 新增 `evidence-frames/` 前缀上传封装
- [ ] 验证：同一视频重复提交能命中 VideoContext 复用，不重新跑 ASR/OCR

### 阶段 4：DeepSeekUtils 结构化输出改造
- [ ] 新增结构化输出解析包装（JSON 解析失败重试一次的通用方法）
- [ ] 新增 `plan()`/`execute()`/`critique()`/`replan()` 四个方法及对应 Prompt
- [ ] 新增 `AnalysisResult`/`AgentPlan`/`CriticResult` DTO（title/conclusions/evidence/suggestions 结构）
- [ ] 验证：单独调用 Executor 产出结构化 JSON，人工核对字段完整性

### 阶段 5：Agent 闭环编排 + 证据校验 + 预算控制
- [ ] 新增 `AgentLoopService`（Planner→Executor→Critic 循环，轮次/时长预算检查）
- [ ] 新增 `EvidenceVerificationService`（证据真实性代码校验）
- [ ] 新增 Redis Checkpoint（Plan/Draft/Critique 阶段快照）
- [ ] `AiService.asyncAnalyze()` 接入 `AgentLoopService`，替换原 `generateSummaryFromText()` 调用
- [ ] `media_ai_analysis` 表新增 `result_json`/`critic_passed`/`agent_rounds` 列（数据库迁移脚本）
- [ ] 结构化结果 → Markdown 摘要渲染逻辑（保证前端兼容）
- [ ] 上调 `AnalysisCompensationScheduler` 卡死阈值（多轮 Agent 循环耗时增加）
- [ ] 验证：完整跑一次端到端分析，核对 Critic 校验、证据绑定、多轮重跑触发条件

### 阶段 6：前端适配 + 收尾
- [ ] `ResultSidebar.vue` 展示证据来源（时间戳 + 证据帧缩略图，可选，视优先级）
- [ ] 前端对 `critic_passed=false`（警告态）结果的视觉区分（如"结果可能不完整"提示）
- [ ] 更新 `ARCHITECTURE.md` 与 `CLAUDE.md`"已知陷阱"章节（补充 Tesseract 部署依赖）
- [ ] 全链路回归测试 + 性能/成本评估（见第五章）

---

## 五、风险与兼容性

### 5.1 成本与延迟影响（需要重点评估）

单次分析的 LLM 调用次数从 **1 次**（现状）增加到 **最多 4-5 次**（Plan + Execute + Critic，未通过时 + Replan + Execute + Critic）。这意味着：
- SiliconFlow API 调用量成倍增长，需重新评估账号配额与费用预算。
- 单次分析耗时从"1 次 ASR + 1 次 LLM"增加到"分段 ASR（可能更慢，取决于并发） + OCR + 最多 5 次 LLM"，端到端延迟显著上升。
- 现有全局限流 `limit:ai:global`（30次/分）限制的是**用户请求次数**，不是 LLM 调用次数——实际打到 SiliconFlow 的请求量会是请求次数的 3-5 倍，需要额外评估 SiliconFlow 侧的并发/QPS 限制是否够用。

**建议**：阶段 5 上线前先在测试环境用真实账号跑一批样本，实测平均耗时与调用量，据此决定是否需要下调 `agent.budget.max-rounds` 为默认 1（先不开多轮，只做 Planner+Executor+Critic 单轮结构化，验证过再开多轮）。

### 5.2 向后兼容

- 数据库新增列全部允许 NULL，不删除任何现有列，历史数据不受影响。
- `AiAnalysisStrategy` 接口签名不变，`/debug/transcribe` 纯文字提取路径基本不受影响（只是内部实现从整段识别变为分段拼接）。
- 前端 `marked` 渲染路径不变，Markdown 摘要仍由后端渲染好写入 `summary`。
- `force=true` 强制重新生成的现有语义延伸到 VideoContext 级复用，保持一致。

### 5.3 测试策略

- 单元测试：`EvidenceVerificationService`（证据匹配规范化逻辑）、`AgentLoopService`（轮次/预算终止条件）参照现有 `AgentCheckpointServiceTest`/`AgentExecutionBudgetTest` 风格（DOVideo 项目已有同类测试可参考写法，不可直接复用代码）。
- 集成测试：沿用项目现有 `/analyze-video` skill 走端到端验证。
- 手动验收：至少准备 3 类测试视频（纯语音无字幕、含 PPT 字幕、含代码截图）分别验证 OCR 增益是否明显。

---

## 六、未决问题（需要在实施前进一步确认）

1. **成本预算上限**：是否需要引入 DOVideo 的"最大成本预算"维度？取决于 SiliconFlow 计费方式（按 token 还是固定价），目前 VideoCourseAI 未接入计费统计，需要先确认账单获取方式。
2. **多轮默认是否开启**：建议先上线单轮（Planner+Executor+Critic 各跑一次，不重试），观察实际证据校验通过率，再决定是否开启第二轮重跑。
3. **OCR 是否对所有视频默认开启**：部分纯语音类视频（播客、访谈）跑 OCR 是纯浪费算力，可考虑视频时长/类型启发式判断是否跳过 OCR 分支，或直接默认全开，视第一批实测的 OCR 命中率决定。
4. **Tesseract 部署方式**：是否随应用打包（Docker 镜像内置）还是要求宿主机预装，需要在部署方案里明确，避免和现有 `tool.ffmpeg.dir`/`tool.ytdlp.path` 一样出现"仅 Windows 硬编码路径"的问题。
