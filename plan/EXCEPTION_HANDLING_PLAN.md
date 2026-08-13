# AI 调用链路异常处理优化策略计划书

> 本文档以 DOVideo-AI-main 的异常处理策略为参照基准，为 VideoCourseAI 设计一套分层、可重试、可追溯的异常处理改造方案。
> 最后更新：2026-08-13

---

## 一、背景与目标

### 1.1 参照基准：DOVideo-AI 的异常处理哲学

DOVideo-AI 把异常当作「需要被精确分类、可重试判断、可追溯的第一等公民」，其核心特征：

| 特征 | DOVideo-AI 的做法 |
|------|------------------|
| 异常去向 | **层层上抛**，在消息消费边界（`VideoAnalysisConsumer`）做最终决策 |
| 异常类型 | 语义化 `IllegalStateException` + `IllegalArgumentException` + 自定义异常（`BudgetExceededException` 等） |
| 重试分层 | **双层**：模型级（`chat()` 判可重试性）+ 消息级（Consumer 判永久失败，`maxReconsumeTimes=2`） |
| 失败终点 | 分级收敛：永久失败 → 失败台账 + 死信主题 + 状态事件；瞬时失败 → MQ 重投 |
| 日志观测 | SLF4J 结构化日志 + `traceId` 贯穿全链路 |
| 信息泄漏 | 对外受控文案，异常细节仅进日志 |

### 1.2 改造目标

在不大动 VideoCourseAI 现有架构（分片上传、状态字段化、MinIO、RocketMQ）的前提下，把 AI 调用链路的异常处理从「**吞异常落库 + 一律 RuntimeException**」升级为「**分类上抛 + 双层重试 + 失败台账 + 结构化日志**」，对齐 DOVideo-AI 的治理水平。

---

## 二、现状问题清单

对照 DOVideo-AI，VideoCourseAI 当前 AI 调用链路的异常处理存在以下问题：

| # | 问题 | 现状代码位置 | 后果 |
|---|------|-------------|------|
| 1 | **异常被吞掉，不上抛** | `AiService.asyncAnalyze` 大 `catch(Exception)` → 写 DB 字段后正常返回 | MQ 消费者无法感知失败，`VideoAnalysisConsumer` 的兜底 catch 形同虚设 |
| 2 | **异常类型单一** | `DeepSeekUtils`/`AliyunAsrUtils`/`AliyunDeepSeekStrategy` 全程 `RuntimeException` | 调用方无法按类型区分「参数错误/网络抖动/服务端 500」 |
| 3 | **重试逻辑重复且位置过低** | `DeepSeekUtils.callWithRetry`、`AliyunAsrUtils.audioToText` 各埋一段固定 3 次重试 | 两段重复代码，重试耗尽后抛普通 `RuntimeException`，MQ 层无二次决策能力 |
| 4 | **无失败台账 / 死信** | 消费失败消息即消失，无任何落库记录 | 失败任务无法排查、无法人工重放 |
| 5 | **日志混乱** | `System.out/err.println` + `e.printStackTrace()` 遍布链路 | 无结构化日志，多线程下无法关联一条任务 |
| 6 | **信息泄漏** | `aiSummary = "❌ 分析失败: " + e.getMessage()` 直接写给前端 | 底层 HTTP 错误体、DeepSeek/ASR 细节暴露给前端 |
| 7 | **ErrorCode 语义混乱** | `ErrorCode` 的 `code` 是 400/404 三位数，`ApiExceptionHandler.business()` 用 `HttpStatus.resolve(code.code())` 反查 | 业务码与 HTTP 状态耦合，无法扩展细分场景（如 429 限流） |
| 8 | **Controller 返回裸 String** | `DebugController.aiAnalyze` 返回 `"✅ 任务已投递..."`，内部 try-catch 打印 | 响应结构不统一，前端无法按 `code` 判断 |
| 9 | **FFmpeg 失败丢失原因** | `FfmpegUtils.extractAudio` 返回 `boolean`，`catch` 后返回 `false` | 调用方只知道「失败」，不知道是超时、退出码异常还是文件不存在 |
| 10 | **异步化吞掉 MQ 重投** | `VideoAnalysisConsumer` 用 `CompletableFuture.runAsync` 丢线程池后立即返回 | `onMessage` 立即 ACK，异常在线程池内抛不出，RocketMQ 无法重投 |

---

## 三、目标异常体系设计

### 3.1 分层职责模型

改造后，异常沿调用链**逐层上抛**，每层只做自己该做的处理：

```
┌─────────────────────────────────────────────────────────────┐
│ L5 Controller（DebugController / ApiExceptionHandler）        │
│   职责：统一 Result 返回；抛 BusinessException 交给全局处理器    │
├─────────────────────────────────────────────────────────────┤
│ L4 消费层（VideoAnalysisConsumer）—— 最终决策点                │
│   职责：永久失败判定 → 台账 + 死信 + 写 FAILED；瞬时失败 → 重投   │
├─────────────────────────────────────────────────────────────┤
│ L3 服务层（AiService）                                        │
│   职责：写 aiStatus=FAILED 落库（保证前端可见）+ 异常继续上抛      │
├─────────────────────────────────────────────────────────────┤
│ L2 策略层（AliyunDeepSeekStrategy）                           │
│   职责：编排 FFmpeg/ASR/DeepSeek，异常透传（不再 catch 打印）     │
├─────────────────────────────────────────────────────────────┤
│ L1 工具层（DeepSeekUtils / AliyunAsrUtils / FfmpegUtils）      │
│   职责：模型级重试 + 语义化抛异常（区分可重试/不可重试）           │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 异常类型约定（对齐 DOVideo-AI）

沿用 DOVideo-AI 的「异常类型即语义」惯例，VideoCourseAI 引入两种约定，无需新增过多自定义异常类：

| 异常类型 | 语义 | 消费层处理 | 触发场景 |
|---------|------|-----------|---------|
| `IllegalArgumentException` | 参数/确定性错误，**不可重试** | 判为永久失败 → 台账 + 死信 | 视频路径为空、磁盘文件不存在、模型返回 4xx |
| `IllegalStateException` | 运行时失败，**可重试** | 未达上限 → MQ 重投；达上限 → 台账 | 网络抖动、模型 5xx、超时、ASR 服务端错误 |
| `BusinessException`（已有） | 携带 `ErrorCode` 的业务语义 | 同步接口由 `ApiExceptionHandler` 映射 | Controller 层可预期失败 |

> **不新增自定义异常类的理由**：VideoCourseAI 没有 DOVideo-AI 的「预算耗尽」「上下文未就绪」等专属领域语义，`IllegalArgumentException` / `IllegalStateException` 二分法已能覆盖「不可重试 / 可重试」的全部需求。若未来引入成本预算，再补 `BudgetExceededException` 不迟。

### 3.3 失败台账

新增一张失败记录表，作为消费层永久失败的落点（对标 DOVideo-AI 的 `FailedAnalysisTask`）：

```sql
CREATE TABLE failed_analysis_task (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    media_id     BIGINT       NOT NULL COMMENT '关联 media_files.id',
    error_type   VARCHAR(64)  COMMENT '异常类型（IllegalArgumentException/IllegalStateException/...）',
    error_msg    VARCHAR(2000) COMMENT '错误摘要（受控，不含堆栈）',
    attempts     INT          DEFAULT 1 COMMENT '累计投递次数',
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '首次失败时间'
) COMMENT 'AI 分析失败台账';
```

对应新增 `entity/FailedAnalysisTask.java`、`mapper/FailedAnalysisTaskMapper.java`、`service/FailedAnalysisTaskService.java`（只提供 `record()` 一个方法）。

### 3.4 ErrorCode 重构（P1，可选）

为消除 `HttpStatus.resolve(code.code())` 的隐式耦合，给 `ErrorCode` 增加独立的 `httpStatus` 字段，并补齐缺失的错误码：

```java
public enum ErrorCode {
    SUCCESS(0, "成功", HttpStatus.OK),
    INVALID_ARGUMENT(400, "请求参数不合法", HttpStatus.BAD_REQUEST),
    VALIDATION_FAILED(400, "请求参数校验失败", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(401, "未认证", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(403, "无访问权限", HttpStatus.FORBIDDEN),
    NOT_FOUND(404, "资源不存在", HttpStatus.NOT_FOUND),
    CONFLICT(409, "资源状态冲突", HttpStatus.CONFLICT),
    RATE_LIMITED(429, "请求过于频繁", HttpStatus.TOO_MANY_REQUESTS),   // 新增
    UNPROCESSABLE(422, "请求无法处理", HttpStatus.UNPROCESSABLE_ENTITY),
    INTERNAL_ERROR(500, "服务暂时不可用", HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE(503, "服务不可用", HttpStatus.SERVICE_UNAVAILABLE); // 新增
    // ... code() / httpStatus() / defaultMessage() 三字段
}
```

> **关于五位数的取舍**：DOVideo-AI 用五位数业务码（`40000`/`42200`）。VideoCourseAI 前端只判断 `code == 0` 成功、非 0 失败，改成五位数对前端透明。但为避免一次性改动过大，本计划先补 `httpStatus` 字段 + 缺失错误码，五位数改造留作后续独立任务。

---

## 四、分层改造方案

### 4.1 L1 工具层（DeepSeekUtils / AliyunAsrUtils / FfmpegUtils）

**DeepSeekUtils.callWithRetry**（对标 DOVideo-AI `chat()`）：

1. 保留 3 次重试，但把重试判定从「只认 `>= 500`」细化为「可重试 / 不可重试」：
   - 可重试：HTTP `408 / 429 / >= 500`、`IOException` 网络异常
   - 不可重试：HTTP `4xx`（400/401/403/404 等）
2. 异常语义化抛出：
   - 不可重试 → `throw new IllegalArgumentException("DeepSeek 请求被拒绝: HTTP " + code, e)`
   - 重试耗尽 → `throw new IllegalStateException("DeepSeek 请求失败，已重试 3 次", e)`
3. `println` / `System.err` 替换为 `LoggerFactory.getLogger(DeepSeekUtils.class)`。
4. 解析响应失败（`choices` 缺失、`content` 为空）同样抛 `IllegalStateException`。

**AliyunAsrUtils.audioToText**：与 `DeepSeekUtils` 采用同一套重试判定与语义化抛异常规则（消除两段重复逻辑），文件不存在时抛 `IllegalArgumentException`。

**FfmpegUtils.extractAudio**：把 `return boolean` 改为「抛异常」契约（对标 DOVideo-AI 的「失败必须带原因」）：
- 退出码非 0 → `throw new IllegalStateException("FFmpeg 退出码 " + exitCode)`
- 超时 → `throw new IllegalStateException("FFmpeg 超时")`
- 文件不存在 → `throw new IllegalArgumentException(...)`（在调用方 `AliyunDeepSeekStrategy` 检查更合适）

> 兼容性说明：`FfmpegUtils.extractAudio` 现有两处调用（`AliyunDeepSeekStrategy.processVideoToText`、`DebugController.download`），改造需同步调整这两个调用点。`download` 接口失败时由 `ApiExceptionHandler` 统一转 500。

### 4.2 L2 策略层（AliyunDeepSeekStrategy）

- 删除 `processVideoToText` 里的 `catch (Exception e) { e.printStackTrace(); throw new RuntimeException(...) }`，改为**异常透传**：只保留 `finally` 清理临时 MP3 文件，不再包裹。
- 输入校验（路径空 / 本地文件不存在）由 `RuntimeException` 改为 `IllegalArgumentException`（不可重试，消费层据此快速收敛）。
- `FfmpegUtils.extractAudio` 改抛异常后，删除 `if (!success) throw new RuntimeException(...)` 判断。

### 4.3 L3 服务层（AiService）

核心改造：**「写库保证前端可见」与「上抛保证 MQ 可决策」解耦**，两者都要做。

```java
public void asyncAnalyze(Long mediaId) {
    MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
    if (mediaFile == null) {
        throw new IllegalArgumentException("文件不存在: " + mediaId);   // 不可重试，直接收敛
    }
    mediaFile.setAiStatus(AiStatus.PROCESSING.name());
    mediaFileMapper.updateById(mediaFile);

    boolean transcribed = false;   // 标记 transcribe 是否已成功，供 catch 精确维护 transcriptStatus
    try {
        String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
        mediaFile.setTranscriptText(text);
        mediaFile.setTranscriptStatus(AiStatus.SUCCESS.name());
        transcribed = true;          // ← 阶段标记点

        String summary = aiAnalysisStrategy.generateSummary(mediaFile.getFilePath());
        mediaFile.setAiSummary(summary);
        mediaFile.setAiStatus(AiStatus.SUCCESS.name());
        mediaFileMapper.updateById(mediaFile);

        evictCache(mediaFile);
    } catch (Exception e) {
        // 1) 失败态一致性：transcribe 未完成 → transcriptStatus 同步置 FAILED，
        //    否则前端「全量文字提取」读到 NONE，误判为「从未尝试」而诱导用户重复提交必败重跑。
        if (!transcribed) {
            mediaFile.setTranscriptStatus(AiStatus.FAILED.name());
        }
        // 2) 落库：保证前端轮询能看到 FAILED（受控文案，不泄漏堆栈）
        markFailed(mediaFile, e);
        // 3) 上抛：让消费层决定重试还是收敛
        if (e instanceof IllegalArgumentException iae) {
            throw iae;                                  // 永久失败，原样透传
        }
        throw new IllegalStateException("AI 分析失败", e); // 瞬时失败，包装上抛
    }
}
```

关键点：

1. **失败态一致性（本次必须修复的遗留 bug）**：`asyncAnalyze` 是「transcribe → summarize」两步流水线，catch 块拿不到「失败发生在哪一步」，因此必须用局部标记 `transcribed` 区分两种失败——transcribe 失败 → `transcriptStatus=FAILED`；仅 summarize 失败 → `transcriptStatus` 保持 `SUCCESS`。旧代码只设 `aiStatus=FAILED`，`transcriptStatus` 停留 `NONE`，前端 `useMedia.js` 的 `transcribe()` 会误判「从未尝试」而允许重复提交一次必然失败的重跑。
2. `markFailed` 写入 `aiStatus=FAILED` + `aiSummary="❌ 分析失败，请稍后重试"`（**受控文案**，不再拼接 `e.getMessage()`），同时删缓存。`transcriptStatus` 的一致性维护（第 1 点）必须在调用 `markFailed` 之前完成，让两字段在同一次 `updateById` 里落库。
3. `asyncTranscribe` 采用同一模式（其 catch 已正确设 `transcriptStatus=FAILED`，本次仅需统一受控文案与上抛语义）。
4. `evictCache` 提取为私有方法，消除 `asyncAnalyze` / `asyncTranscribe` 里重复的缓存删除代码。
5. `System.out/err.println` 全部替换为 `log`。

### 4.4 L4 消费层（VideoAnalysisConsumer）—— 最终决策点

**关键决策：消费模型选择**（对标 DOVideo-AI 同步消费）：

| 方案 | 描述 | 取舍 |
|------|------|------|
| **A（推荐）同步消费** | 去掉 `CompletableFuture.runAsync`，`onMessage` 内直接调 `aiService.asyncAnalyze`，异常抛出交给 RocketMQ 重投 | 对齐 DOVideo-AI；依赖 MQ 自身并发（`consumeThreadNumber/consumeThreadMax`），重试机制完整 |
| B 保留异步化 | 保留线程池，`future.whenComplete` 里自行实现「应用级重试 + 永久失败判定」 | 保留已配的 `aiTaskExecutor`，但需自己重造 MQ 已有的重投/限流能力，复杂度更高 |

> **推荐方案 A 的理由**：当前 `CompletableFuture.runAsync` 让 `onMessage` 立即 ACK，异常在线程池内无处可去（问题 #10 的根因）。改为同步消费后，异常能自然传导给 RocketMQ，配合 `maxReconsumeTimes=2` 即可获得「最多 3 次投递」的完整重试链路，无需另写重试代码。线程池若仍需，可改由 MQ 的 `consumeThreadNumber` 承接。

**消费层改造内容**（对齐 DOVideo-AI `VideoAnalysisConsumer.onMessage`）：

1. 注解加 `maxReconsumeTimes = 2`（2 次重投 = 最多 3 次投递）。
2. `onMessage` 内 try-catch 分级处理：
   - `catch (IllegalArgumentException e)`：**永久失败**，写台账 `failedTaskService.record(...)` + 写 `aiStatus=FAILED` + （可选）转投死信主题，正常 ACK。
   - `catch (Exception e)`：**瞬时失败**，`throw new IllegalStateException("视频分析消费失败", e)` 交给 RocketMQ 重投；重投耗尽后 Broker 转入死信队列（RocketMQ 默认 `%DLQ%` 组）。
3. 增加 `isPermanentFailure(Throwable)` 辅助方法，沿 cause 链（`MAX_CAUSE_DEPTH=16`）识别 `IllegalArgumentException` / `NoSuchElementException` / `SecurityException`，防止 `IllegalStateException` 层层包装后认不出根因。
4. `markAsFailed` 与 `AiService.markFailed` 合并去重，统一由服务层负责写 FAILED。

> **死信主题（可选，P2）**：RocketMQ 自带 `%DLQ%` 重试耗尽队列，可先依赖默认行为；若要显式死信主题 + 台账查询界面，再参照 DOVideo-AI 的 `video-analysis-dead-topic` 扩展。

### 4.5 L5 Controller 层（DebugController / ApiExceptionHandler）

**DebugController**：

- `aiAnalyze` / `transcribe` 返回值从裸 `String` 改为 `Result<String>`（或 `Result<Void>`），错误场景抛 `BusinessException` 交给全局处理器，不再 `catch(Exception) printStackTrace` 返回字符串。
- 限流、重复提交、文件不存在分别映射 `BusinessException(ErrorCode.RATE_LIMITED, ...)` / `CONFLICT` / `NOT_FOUND`。

**ApiExceptionHandler**：

- `business()` 去掉 `HttpStatus.resolve(code.code())`，改用 `ErrorCode.httpStatus()`。
- 补齐 `IllegalStateException → 500`（或按需 409）、`BusinessException` 各错误码的映射（对齐 DOVideo-AI 已覆盖 400/404/409/422/429）。
- 同步接口（`download`、上传链路）的异常也统一走此处理器，不再手写 `ResponseEntity.internalServerError()`。

---

## 五、实施计划

按优先级分三档：**P0 核心链路（必做）**、**P1 加固（推荐）**、**P2 增强（可选）**。

### P0：核心链路改造（异常上抛 + 双层重试 + 台账）

| 阶段 | 任务 | 涉及文件 |
|------|------|---------|
| 1 | 失败台账表 + 实体/Mapper/Service | 新增 `failed_analysis_task` 表、`entity/FailedAnalysisTask.java`、`mapper/FailedAnalysisTaskMapper.java`、`service/FailedAnalysisTaskService.java` |
| 2 | 工具层语义化抛异常 + SLF4J | `utils/DeepSeekUtils.java`、`utils/AliyunAsrUtils.java`、`utils/FfmpegUtils.java` |
| 3 | 策略层异常透传 | `strategy/impl/AliyunDeepSeekStrategy.java` |
| 4 | 服务层「落库 + 上抛」解耦 | `service/AiService.java` |
| 5 | 消费层同步化 + 永久失败判定 + 台账 | `consumer/VideoAnalysisConsumer.java` |
| 6 | 受控文案（信息泄漏治理） | `service/AiService.java`（`markFailed`） |

### P1：加固（ErrorCode + Controller 统一）

| 阶段 | 任务 | 涉及文件 |
|------|------|---------|
| 7 | ErrorCode 补 `httpStatus` + 缺失码 | `common/ErrorCode.java` |
| 8 | ApiExceptionHandler 补映射、去 `HttpStatus.resolve` | `controller/ApiExceptionHandler.java` |
| 9 | DebugController 返回 `Result` + 抛 `BusinessException` | `controller/DebugController.java` |

### P2：增强（可选）

| 阶段 | 任务 | 涉及文件 |
|------|------|---------|
| 10 | 显式死信主题 + 台账查询接口 | `consumer/VideoAnalysisConsumer.java`、新增 `controller/FailedTaskController.java` |
| 11 | ErrorCode 五位数业务码 | `common/ErrorCode.java`（前端无需改动，`code==0` 判断兼容） |
| 12 | 全链路日志关联键（简易 traceId） | `AiService` 生成 `mediaId` 关联键透传各层日志 |

---

## 六、文件变更清单（预估）

### 新增文件

```
server/src/main/java/com/example/server/entity/FailedAnalysisTask.java          # 失败台账实体
server/src/main/java/com/example/server/mapper/FailedAnalysisTaskMapper.java    # MyBatis-Plus Mapper
server/src/main/java/com/example/server/service/FailedAnalysisTaskService.java  # 仅 record()
server/src/main/resources/db/V3__add_failed_analysis_task.sql                    # 建表脚本
```

### 修改文件

```
server/src/main/java/com/example/server/utils/DeepSeekUtils.java              # 语义化抛异常 + SLF4J
server/src/main/java/com/example/server/utils/AliyunAsrUtils.java             # 同上，统一重试规则
server/src/main/java/com/example/server/utils/FfmpegUtils.java                # boolean → 抛异常
server/src/main/java/com/example/server/strategy/impl/AliyunDeepSeekStrategy.java  # 异常透传
server/src/main/java/com/example/server/service/AiService.java                # 落库 + 上抛解耦
server/src/main/java/com/example/server/consumer/VideoAnalysisConsumer.java   # 同步消费 + 永久失败判定 + 台账
server/src/main/java/com/example/server/common/ErrorCode.java                 # +httpStatus +缺失码
server/src/main/java/com/example/server/controller/ApiExceptionHandler.java   # 补映射 + 去 resolve
server/src/main/java/com/example/server/controller/DebugController.java       # 返回 Result + 抛 BusinessException
server/src/main/resources/db/schema.sql                                       # 建表同步 failed_analysis_task
```

---

## 七、验证方式

1. **编译**：在 IDEA（JDK 21）内 `mvn clean compile`，确认无编译错误。
2. **永久失败收敛**：构造「文件不存在」场景（本地路径指向不存在文件），触发 AI 分析，确认：
   - 消息**不**重复投递（RocketMQ 控制台观察投递次数为 1）；
   - `failed_analysis_task` 新增一条记录，`error_type=IllegalArgumentException`；
   - `aiStatus=FAILED`，前端显示受控文案（不含堆栈）。
3. **瞬时失败重试**：临时让 DeepSeek 返回 500（或断网），确认：
   - 消息投递 1→2→3 次后进入 `%DLQ%`；
   - 每次投递 `aiStatus` 保持 `PROCESSING`（不提前写 FAILED），最终超限后写 FAILED。
4. **重试期间前端体验**：`PROCESSING` 状态持续转圈，不会闪现中间态（回归上一份 `AI_STATUS_FIELD_PLAN` 的场景）。
5. **信息泄漏回归**：grep 确认 `aiSummary` 不再出现 `e.getMessage()` / `getMessage()` 拼接。
6. **日志回归**：grep 确认 AI 链路 `System.out/err.println` 与 `printStackTrace` 清零，替换为 `log.xxx`。
7. **同步接口回归**：上传、分片合并、列表、下载接口错误响应统一为 `Result` 结构，`code` 正确。

---

## 八、风险与回滚

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 消费模型从异步改同步，若 MQ 并发配置不当，消费吞吐下降 | 任务排队变长 | 通过 `consumeThreadNumber/consumeThreadMax` 调并发，压测验证吞吐 |
| `FfmpegUtils` 改抛异常，波及 `DebugController.download` | 下载接口行为变化 | 同步改 `download` 的异常处理，回归下载场景 |
| 重试期间 `aiStatus` 保持 PROCESSING，若消息丢失则任务卡死 | 前端永久转圈 | 依赖 RocketMQ 投递保证 + `ACTIVE_TTL` 式过期兜底（可选，参考 DOVideo-AI 的 activeKey） |
| ErrorCode 加字段破坏现有引用 | 编译错误 | 枚举保持 `code()` 语义不变，仅新增字段，存量调用点不动 |
| 台账表写入失败阻塞主流程 | 任务失败但无记录 | `record()` 内部 try-catch，写入失败仅记日志、不阻断异常上抛（对齐 DOVideo-AI 的 `addSuppressed` 策略） |

**回滚策略**：改动集中在 AI 分析链路，不触及上传/分片/合并核心路径。若 P0 改造引发线上问题，可单独回滚 `VideoAnalysisConsumer` 与 `AiService` 两个文件恢复「吞异常落库」行为，台账表保留不删（多一张表无副作用）。

---

## 九、与既有文档的关系

- 本计划建立在 [`AI_STATUS_FIELD_PLAN.md`](AI_STATUS_FIELD_PLAN.md) 之上：`aiStatus` 状态字段是「失败落库」的载体，异常上抛不改变状态流转（`NONE→PENDING→PROCESSING→SUCCESS/FAILED`），只是让「FAILED」的写入时机与「是否重试」解耦。
- 分片上传（[`CHUNKED_UPLOAD_PLAN.md`](CHUNKED_UPLOAD_PLAN.md)）链路的异常处理不在本计划范围，但其 `BusinessException` 用法与本计划保持一致。
