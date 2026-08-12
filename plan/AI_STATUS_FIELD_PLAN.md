# AI 分析 / 文字提取状态字段化改造计划与执行记录

> 本文档记录「将 AI 分析、文字提取的中间态/结果态从文案字段中剥离为独立状态字段」的完整设计、实际实施以及关键决策。
> 最后更新：2026-08-13

---

## 〇、背景与问题

### 现状

AI 分析、文字提取两条链路的「状态」长期混在业务文案字段里，靠字符串匹配来推断：

| 链路 | 状态载体 | 各阶段文案 |
|------|----------|-----------|
| AI 分析 | `ai_summary` | 提交时 `[MQ] 已进入消息队列，等待调度...`；成功为带 `##` 的 Markdown；失败为 `❌ 分析失败: ...` |
| 文字提取 | `transcript_text` | 空 = 未提取；成功为 ASR 原文；失败为 `❌ ...` / `FFmpeg 转换失败` / `处理异常` |

### 触发的 Bug

前端 `useMedia.js` 的 `aiAnalyze()` 第一个分支用**排除法**判断「已有结果」：

```js
if (item.aiSummary && !item.aiSummary.includes("任务已") && !item.aiSummary.includes("正在")) { ... }
```

而后端投递 MQ 时写入的中间态文案 `[MQ] 已进入消息队列，等待调度...` 既不含「任务已」也不含「正在」，被**误判为最终结果**。于是：

1. 首次点击「AI 分析」→ 正常转圈。
2. 关闭侧边栏（轮询定时器仍在跑）→ 列表刷新拿到中间态文案。
3. 重新打开侧边栏 → 命中「已有结果」分支 → 直接显示 `[MQ] 已进入消息队列...`，且 `loading = false` 关掉了转圈。

### 根因

**状态靠文案表达，而非独立字段**。中间态、结果态、失败态混在同一个 `ai_summary` 里，靠 `includes("正在")` / `includes("##")` 猜，必然存在漏判/误判。

---

## 一、方案设计

### 1.1 状态模型

新增枚举 `AiStatus`，5 个值，语义单一：

| 值 | 含义 | AI 分析 | 文字提取 |
|----|------|---------|----------|
| `NONE` | 未处理（默认） | ✓ | ✓ |
| `PENDING` | 已投递 MQ，排队中 | ✓ | ✗（无 MQ 阶段） |
| `PROCESSING` | 正在处理 | ✓ | ✓ |
| `SUCCESS` | 处理完成 | ✓ | ✓ |
| `FAILED` | 处理失败 | ✓ | ✓ |

### 1.2 状态流转

```
AI 分析：  NONE → PENDING → PROCESSING → SUCCESS / FAILED
                (投递MQ)    (消费者接单)    (结果落库)

文字提取： NONE → PROCESSING → SUCCESS / FAILED
                (提交线程池)     (结果落库)
```

### 1.3 数据库字段

`media_files` 表新增两个字段（不复用已有的 `status` 字段，因其已承载上传状态 `UPLOADED`/`COMPLETED`）：

```sql
ai_status         VARCHAR(32) DEFAULT 'NONE' COMMENT 'AI分析状态: NONE/PENDING/PROCESSING/SUCCESS/FAILED',
transcript_status VARCHAR(32) DEFAULT 'NONE' COMMENT '文字提取状态: NONE/PROCESSING/SUCCESS/FAILED'
```

---

## 二、关键设计决策

| 决策点 | 选择 | 原因 |
|--------|------|------|
| 不复用 `status` 字段 | 新增 `ai_status` / `transcript_status` | `status` 已被上传状态占用（`MediaController`、`ChunkUploadService` 写入 `COMPLETED`），语义冲突 |
| 枚举放哪个包 | `common` 包（与 `ErrorCode` 并列） | 项目既有惯例：`common/ErrorCode.java`、`common/Result.java` 集中放通用类型/枚举；不放入 `dto`（那是数据载体），不另开 `enums`（过度拆分） |
| `PROCESSING` 是否删缓存 | **不删** | 任务生命周期内 DB 只被轮询命中 2 次（投递、完成）。前端把 `PENDING`/`PROCESSING` 都渲染为「进行中转圈」，无需区分，删缓存只会换来一次无意义的 DB 读 |
| 是否新增 Redis 热缓存 | 否 | `MediaController.list` 已有 30min Redis 缓存 + Cache Aside 失效（上传/投递/完成/删除时 delete），前端 3s 轮询绝大部分命中缓存，DB 压力极小 |
| 失败判定 | `isFailureText()` 按**固定前缀**精确匹配 | 后端各环节失败返回固定前缀的错误文案（`❌`、`FFmpeg 转换失败`、`处理异常`、`AI request failed`），前缀匹配可避免误伤正常的 ASR 中文文本 |
| 防重复提交 | 改为按状态判断 | `PENDING`/`PROCESSING` 时拒绝重复提交；`SUCCESS`/`FAILED`/`NONE` 允许（支持重新分析已完成文件） |
| 历史数据 | 迁移脚本回填 | 按 `ai_summary` 含 `##` → SUCCESS、含错误标志 → FAILED；`transcript_text` 同理，前端无需兼容判断 |
| 页面刷新兜底 | 前端按状态恢复轮询 | 重开侧边栏时若状态为进行中但定时器已丢失（页面刷新），自动 `startPolling` 恢复 |

---

## 三、实施完成清单

### 阶段一：数据库 ✅

| 任务 | 状态 | 说明 |
|------|------|------|
| 1.1 迁移脚本 | ✅ | 新增 `db/V2__add_ai_status.sql`：加两列 + 4 段回填 UPDATE |
| 1.2 建表脚本同步 | ✅ | `db/schema.sql` 的 `media_files` 建表语句加 `ai_status`、`transcript_status` |
| 1.3 迁移执行 | ✅ | 已在 `mysql-media` 容器执行，4 条历史数据全部回填为 `SUCCESS` |

### 阶段二：后端 ✅

| 任务 | 状态 | 说明 |
|------|------|------|
| 2.1 枚举 | ✅ | 新增 `common/AiStatus.java`（NONE/PENDING/PROCESSING/SUCCESS/FAILED） |
| 2.2 实体字段 | ✅ | `entity/MediaFile.java` 加 `aiStatus`、`transcriptStatus` |
| 2.3 投递写入 | ✅ | `DebugController.aiAnalyze` 写 `PENDING` + 清空旧 `aiSummary` + 按状态防重复 |
| 2.4 文字提取提交 | ✅ | `DebugController.transcribe` 写 `PROCESSING` + 删缓存 + 按状态防重复 |
| 2.5 AI 处理状态机 | ✅ | `AiService.asyncAnalyze` 写 `PROCESSING→SUCCESS/FAILED`，顺带维护 `transcriptStatus` |
| 2.6 文字提取状态机 | ✅ | `AiService.asyncTranscribe` 写 `SUCCESS/FAILED`（含 catch 兜底 + 删缓存） |
| 2.7 失败判定辅助 | ✅ | `AiService.isFailureText()` 前缀精确匹配 |
| 2.8 Consumer 兜底 | ✅ | `VideoAnalysisConsumer.markAsFailed` 补写 `FAILED` |

### 阶段三：前端 ✅

| 任务 | 状态 | 说明 |
|------|------|------|
| 3.1 `aiAnalyze` 判断 | ✅ | 改为按 `aiStatus` 分支：SUCCESS/FAILED→显示；PENDING/PROCESSING→转圈+恢复轮询；NONE→提交 |
| 3.2 `transcribe` 判断 | ✅ | 改为按 `transcriptStatus` 分支，逻辑同上 |
| 3.3 `startPolling` 判断 | ✅ | 完成判定与错误提示均改为按状态字段，移除 `includes("##")` / `includes("失败")` 文案匹配 |

---

## 四、文件变更清单

### 新增文件（2 个）

```
server/src/main/java/com/example/server/common/AiStatus.java        # 状态枚举
server/src/main/resources/db/V2__add_ai_status.sql                  # 迁移脚本
```

### 修改文件（5 个）

```
server/src/main/java/com/example/server/entity/MediaFile.java       # +aiStatus, transcriptStatus
server/src/main/java/com/example/server/controller/DebugController.java   # aiAnalyze/transcribe 状态写入
server/src/main/java/com/example/server/service/AiService.java      # 状态机 + isFailureText()
server/src/main/java/com/example/server/consumer/VideoAnalysisConsumer.java  # markAsFailed 补 FAILED
server/src/main/resources/db/schema.sql                             # 建表加两列
client/src/composables/useMedia.js                                  # 三处状态判断改造
```

---

## 五、验证方式

### 已完成的验证

1. **迁移执行**：`docker exec mysql-media mysql ... < V2__add_ai_status.sql`，无报错。
2. **字段确认**：`SHOW COLUMNS ... LIKE '%status%'` 确认 `ai_status`、`transcript_status` 已存在。
3. **回填确认**：4 条历史数据 `ai_status`/`transcript_status` 均为 `SUCCESS`（`ai_summary` 含 `##`）。
4. **静态一致性**：grep 核对全部 `AiStatus` 引用均指向 `common` 包；旧文案判断（`已进入消息队列`、`contains("正在")`、`includes("##")`）已清零。

### 待验证（需在 IDEA 完成）

1. **编译**：当前 shell 默认 Java 8，JDK 21 仅在 IDEA 中，需在 IDE 内重新编译后端。
2. **回归场景**：
   - 上传 → AI 分析 → **关闭侧边栏再打开** → 应始终转圈直到出结果（不再闪现 `[MQ]...`）。
   - 对已完成文件点「AI 分析」→ 直接显示缓存 Markdown，不重新提交。
   - 文字提取同理。

---

## 六、遗留事项（本次未做，后续可做）

| 事项 | 说明 |
|------|------|
| 修复 DeepSeek 返回错误字符串的已知陷阱 | `DeepSeekUtils` 失败仍返回 `AI request failed: ...` 字符串而非抛异常，本次仅在 `AiService` 层用 `isFailureText()` 识别，未改 `DeepSeekUtils` 本身。更彻底的做法是让策略失败时抛异常，`catch` 统一设 `FAILED` |
| SSE 替代轮询 | 状态字段已就绪，`PENDING/PROCESSING/SUCCESS/FAILED` 可直接作为 SSE 事件的 `status` 字段推送，前端 `Map<id, status>` 的状态值无需再改模型 |
