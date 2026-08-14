# AI 分析链路改造：状态字段化 + 异常处理体系

> 本文档合并自原《AI 分析 / 文字提取状态字段化改造计划》与《AI 调用链路异常处理优化策略计划书》。两份计划属于同一改造体系的两个维度：
>
> - **状态字段化**：把「成功 / 失败」从文案中剥离为独立状态字段，作为结果载体。
> - **异常处理**：把「失败」从「吞异常落库」升级为「分类上抛 + 双层重试 + 失败台账」。
>
> 两者均已实施完成（P0 核心链路 + P1 加固），最后更新 2026-08-13。

---

## 一、背景与问题

AI 分析、文字提取两条链路存在两类互相纠缠的问题：

### 1.1 状态靠文案表达（状态字段化要解决的）

各阶段的「状态」长期混在业务文案字段里，靠字符串匹配推断：

| 链路 | 状态载体 | 各阶段文案 |
|------|----------|-----------|
| AI 分析 | `ai_summary` | 提交时 `[MQ] 已进入消息队列...`；成功为带 `##` 的 Markdown；失败为 `❌ 分析失败: ...` |
| 文字提取 | `transcript_text` | 空 = 未提取；成功为 ASR 原文；失败为 `❌ ...` / `FFmpeg 转换失败` |

前端 `useMedia.js` 用排除法（`!includes("任务已") && !includes("正在")`）判断「已有结果」，导致中间态文案 `[MQ] 已进入消息队列...` 被误判为最终结果——关闭侧边栏再打开时直接显示该文案、关掉转圈。

**根因**：状态靠文案表达而非独立字段，中间态 / 结果态 / 失败态混在一起，靠 `includes` 猜必然漏判。

### 1.2 异常被吞掉（异常处理要解决的）

| 问题 | 现状 | 后果 |
|------|------|------|
| 异常被吞 | `AiService.asyncAnalyze` 大 `catch(Exception)` → 写 DB 后正常返回 | MQ 消费层无法感知失败，无法重试 |
| 异常类型单一 | `DeepSeekUtils` / `AliyunAsrUtils` 全程 `RuntimeException` | 调用方无法区分「参数错误 / 网络抖动 / 服务端 500」 |
| 重试逻辑重复且位置过低 | 两个工具类各埋一段固定 3 次重试 | 重试耗尽抛普通异常，MQ 层无二次决策能力 |
| 无失败台账 / 死信 | 消费失败消息即消失 | 失败任务无法排查、无法重放 |
| 日志混乱 | `System.out/err.println` + `printStackTrace` 遍布链路 | 多线程下无法关联一条任务 |
| 信息泄漏 | `aiSummary = "❌ 分析失败: " + e.getMessage()` 直接写给前端 | 底层错误细节暴露 |
| 异步化吞掉 MQ 重投 | `VideoAnalysisConsumer` 用 `runAsync` 丢线程池后立即返回 | `onMessage` 立即 ACK，异常抛不出，RocketMQ 无法重投 |

---

## 二、方案设计

### 2.1 状态模型

新增枚举 `AiStatus`（放 `common` 包，与 `ErrorCode` 并列），5 个值语义单一：

| 值 | 含义 | AI 分析 | 文字提取 |
|----|------|---------|----------|
| `NONE` | 未处理（默认） | ✓ | ✓ |
| `PENDING` | 已投递 MQ，排队中 | ✓ | ✗（无 MQ 阶段） |
| `PROCESSING` | 正在处理 | ✓ | ✓ |
| `SUCCESS` | 处理完成 | ✓ | ✓ |
| `FAILED` | 处理失败 | ✓ | ✓ |

`media_files` 表新增两个字段（不复用已有 `status`，其已被上传状态占用）：

```sql
ai_status         VARCHAR(32) DEFAULT 'NONE' COMMENT 'AI分析状态',
transcript_status VARCHAR(32) DEFAULT 'NONE' COMMENT '文字提取状态'
```

状态流转：

```
AI 分析：  NONE → PENDING → PROCESSING → SUCCESS / FAILED
                (投递MQ)    (消费者接单)    (结果落库)

文字提取： NONE → PROCESSING → SUCCESS / FAILED
                (提交线程池)     (结果落库)
```

### 2.2 异常分层

异常沿调用链逐层上抛，每层只做该做的：

```
L1 工具层（DeepSeekUtils / AliyunAsrUtils）  模型级重试 + 语义化抛异常
L2 策略层（AliyunDeepSeekStrategy）          编排 FFmpeg/ASR/DeepSeek，异常透传
L3 服务层（AiService）                       写 aiStatus=FAILED 落库 + 异常继续上抛
L4 消费层（VideoAnalysisConsumer）           最终决策：永久失败→台账，瞬时失败→重投
L5 Controller（DebugController）             统一 Result + BusinessException
```

采用自定义 `AiAnalysisException extends RuntimeException`，携带 `boolean retryable` 标志：

| 异常 | 语义 | 消费层处理 | 触发场景 |
|------|------|-----------|---------|
| `AiAnalysisException(msg, false)` | 确定性错误，不可重试 | 判永久失败 → 台账 + 正常 ACK | 路径空、文件不存在、模型 4xx |
| `AiAnalysisException(msg, true)` | 瞬时失败，可重试 | 上抛 → RocketMQ 重投 | 网络抖动、5xx/408/429、超时、重试耗尽、响应解析失败、FFmpeg 失败 |

> 不用标准 `IllegalArgumentException` / `IllegalStateException` 二分：`ApiExceptionHandler` 已把 `IllegalStateException → 409`（分片上传「重复合并」依赖），复用会语义冲突。

**失败台账**：新增 `failed_analysis_task` 表（media_id / error_type / error_msg / attempts / created_at），作为消费层永久失败的落点，配套 `entity` / `mapper` / `service`（仅 `record()` 方法）。

### 2.3 ErrorCode 加固（P1）

`ErrorCode` 增加独立 `httpStatus` 字段，消除 `HttpStatus.resolve(code.code())` 的隐式耦合，补齐 `RATE_LIMITED(429)`、`SERVICE_UNAVAILABLE(503)` 等缺失码。前端只判断 `code == 0`，无感知。

---

## 三、实施状态与关键偏离

P0（核心链路）+ P1（加固）已实施完成，P2（增强）未做。实际落地与原计划的关键偏离如下（这是最有价值的「实际 vs 计划」对照）：

| 偏离点 | 原计划 | 实际实施 | 原因 |
|--------|--------|---------|------|
| 异常类型 | `IllegalArgumentException` / `IllegalStateException` 二分 | 自定义 `AiAnalysisException`（`retryable` 标志） | 标准异常已被 `ApiExceptionHandler` 映射（`IllegalStateException→409`），复用会语义冲突 |
| FfmpegUtils | `extractAudio` 由 `boolean` 改抛异常 | 保持 `boolean` 不动，策略层把 `false` 转抛 | 通用工具被 `download`（同步）共用，改抛波及下载接口 |
| asyncTranscribe | 与 asyncAnalyze 同一模式，含上抛 | 只落库 `FAILED` + 受控文案，不上抛不重试 | `@Async` 一次性任务无 MQ 消费层接收重试，上抛只落到兜底 handler |
| 可重试失败落库 | 重试期间保持 `PROCESSING`，耗尽后写 `FAILED` | 每次失败都写 `FAILED`（重投后重新 `PROCESSING`） | RocketMQ 重投耗尽进 `%DLQ%` 后消费层收不到消息、无人写 `FAILED`，前端会无限转圈 |
| transcriptStatus 一致性 | 局部 `transcribed` 标记 | `markFailed` 内 `!SUCCESS.equals(transcriptStatus)` 判断 | 等价且更简单 |
| ASR 空文本 | 未明确 | `audioToText` 补「`text` 空 → 抛 `AiAnalysisException`」 | 修复静音视频被静默标 `SUCCESS` |

关键设计决策（状态字段化部分）：

| 决策点 | 选择 | 原因 |
|--------|------|------|
| 不复用 `status` 字段 | 新增 `ai_status` / `transcript_status` | `status` 已被上传状态占用（`COMPLETED`），语义冲突 |
| 失败判定 | `isFailureText()` 按固定前缀精确匹配 | 前缀匹配避免误伤正常的 ASR 中文文本 |
| 防重复提交 | 按状态判断 | `PENDING`/`PROCESSING` 拒绝；`SUCCESS`/`FAILED`/`NONE` 允许（支持重跑） |
| 历史数据 | 迁移脚本回填 | 按 `##` → SUCCESS、错误标志 → FAILED，前端无需兼容判断 |


---

## 四、文件变更清单

### 新增文件

```
common/AiStatus.java                                      # 状态枚举
exception/AiAnalysisException.java                        # 带 retryable 标志的自定义异常
entity/FailedAnalysisTask.java                            # 失败台账实体
mapper/FailedAnalysisTaskMapper.java                      # 台账 Mapper
service/FailedAnalysisTaskService.java                    # 台账 Service（仅 record()）
db/V2__add_ai_status.sql                                  # 加状态字段 + 回填
db/V3__add_failed_analysis_task.sql                       # 台账建表
```

### 修改文件

```
entity/MediaFile.java                                     # +aiStatus, transcriptStatus
common/ErrorCode.java                                     # +httpStatus +缺失码
controller/DebugController.java                           # 状态写入 + 返回 Result + BusinessException
controller/ApiExceptionHandler.java                       # 补映射 + 去 HttpStatus.resolve
service/AiService.java                                    # 状态机 + 落库/上抛解耦 + isFailureText()
strategy/impl/AliyunDeepSeekStrategy.java                 # 异常透传
consumer/VideoAnalysisConsumer.java                       # 同步消费 + 永久失败判定 + 台账
utils/DeepSeekUtils.java / AliyunAsrUtils.java            # 语义化抛异常 + SLF4J
db/schema.sql                                             # 建表加两列
client/src/composables/useMedia.js                        # 三处状态判断改造
```

---

## 五、验证方式

1. **迁移执行**：`V2__add_ai_status.sql` 已在 `mysql-media` 容器执行，4 条历史数据回填为 `SUCCESS`。
2. **永久失败收敛**：构造「文件不存在」，确认消息不重复投递、`failed_analysis_task` 新增记录、`aiStatus=FAILED` 且前端显示受控文案（不含堆栈）。
3. **瞬时失败重试**：临时让 DeepSeek 返回 500，确认投递 1→2→3 次后进 `%DLQ%`，前端 `PROCESSING` 持续转圈不闪现中间态。
4. **状态回归**：上传 → AI 分析 → 关闭侧边栏再打开 → 应始终转圈直到出结果（不再闪现 `[MQ]...`）。
5. **信息泄漏 / 日志回归**：grep 确认 `aiSummary` 无 `getMessage()` 拼接；AI 链路 `printStackTrace` / `System.out/err` 清零。
6. **同步接口回归**：上传、分片合并、列表、下载错误响应统一为 `Result` 结构，`code` 正确。

---

## 六、遗留事项（本次未做）

| 事项 | 说明 |
|------|------|
| SSE 替代轮询 | 状态字段已就绪，`PENDING/PROCESSING/SUCCESS/FAILED` 可直接作为 SSE 事件的 `status` 字段 |
| 显式死信主题 + 台账查询 | RocketMQ 默认 `%DLQ%` 已可用，查询界面待扩展 |
| ErrorCode 五位数业务码 | 前端 `code==0` 判断兼容，可平滑升级 |
| 全链路日志 traceId | `AiService` 生成 `mediaId` 关联键透传各层日志 |
| FfmpegUtils 抛异常化 | 当前靠策略层把 `false` 转抛，待其脱离同步 `download` 接口后独立改造 |
