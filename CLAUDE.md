# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目

VideoCourseAI — 视频上传（分片续传）→ 本地合并算 MD5 → 提取音频 → ASR 语音转文字 → DeepSeek 智能总结 → Markdown 报告。
Spring Boot 3.5.9 (Java 21, 端口 9090) + Vue 3 (端口 5173) + Docker 中间件。
详细架构见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 命令

```bash
# 中间件
docker-compose up -d          # MySQL(:3307), Redis(:6379), MinIO(:9000/:9001), RocketMQ(:9876/:10911)

# 后端
cd server && mvn clean spring-boot:run    # 无测试

# 前端
cd client && npm install && npm run dev
```

## 核心链路

### 视频上传（统一分片续传）
- 所有文件统一走分片上传（小于 5MB 的文件只有 1 片）：`POST /media/api/chunk/init` → (5MB 切片 × N) `POST /media/api/chunk/upload` → `POST /media/api/chunk/merge` → 写 DB → 清理分片
- **合并为本地合并**：逐分片 `copyObjectTo` 下载到本地临时文件，`DigestOutputStream` 边写边算全文件 MD5 → `uploadLocalFile` 回传 MinIO（一次 IO 完成合并与哈希，替代原 composeObject 服务端合并）
- 分片上传关键 Redis Key：`upload:meta:{uploadId}` (Hash, 48h TTL)、`upload:chunks:{uploadId}` (Set, 已完成序号)
- 合并使用 Redisson 分布式锁 `lock:merge:{uploadId}` (看门狗自动续期)，幂等检查防止重复合并
- 前端并发 3 片上传，每片 3 次指数退避重试，localStorage 持久化 uploadId 支持页面刷新后恢复

### AI 异步分析
提交侧 `GET /debug/ai?id={id}`：查库拿 `file` → 校验 `aiStatus`（PENDING/PROCESSING → 幂等返回成功，不重复投递）→ 提交侧幂等键 `setIfAbsent(analysis:active:{contentHash}, 30s)`（抢不到 → 幂等返回成功；失败回滚）→ 双层限流 `requireAiQuota`（用户 5 次/分 + 全局 30 次/分，真超限 429 / Redis 异常 503）→ 置 PENDING → 发 `AnalysisTaskMsg`（携带 contentHash）到 topic `video-analysis-topic` → 立即返回；发 MQ 失败时 catch 回滚 `aiStatus`/`aiSummary`（连同幂等键一起），避免任务卡死在 PENDING。

消费侧 `VideoAnalysisConsumer`：**快进快出**——只做触发派发（`@Async` 提交 `aiTaskExecutor` 后立即 ACK，队列满捕获 `RejectedExecutionException` 吞掉）。真正执行在 `AiService.asyncAnalyze()`（`@Async`，内容级锁 `lock:analysis:{contentHash}` `tryLock` 抢不到跳过）：结果复用 → 转写（`lock:analysis-context:{contentHash}` + 归属复用 `analysis:context-owner:{contentHash}`）→ `generateSummaryFromText` 总结 → 写 DB → 删缓存 `media:list:user:{userId}`。

**异常决策与重试**：`AiAnalysisException(retryable)` —— 永久失败（retryable=false）落 FAILED + 写台账；瞬时失败（retryable=true）保持 PROCESSING + 刷新 `ai_process_at`，由 `AnalysisCompensationScheduler` 定时补偿重试（`ai_attempts` 达上限 3 落 FAILED），**不再依赖 RocketMQ `reconsumeTimes` 重投**。`asyncAnalyze` 的 `selectById` + 空校验 + 首次 PROCESSING 落库均在 try 内，统一走异常分层（异常不绕过 `markFailed`）。

**状态字段**：`aiStatus` / `transcriptStatus`（枚举 `AiStatus`：NONE/PENDING/PROCESSING/SUCCESS/FAILED），前端 3s 轮询 `GET /media/list` 按状态字段判断，不再靠文案 `includes` 猜测。

### 文字提取
`GET /debug/transcribe?id={id}` → 校验 `transcriptStatus`（PROCESSING → 幂等返回成功，不重复提交）→ 双层限流 `requireTranscribeQuota`（用户 10 次/分 + 全局 60 次/分）→ 置 PROCESSING → `@Async` 提交 `aiTaskExecutor`（核心4/最大8/队列100）→ `transcribeWithReuse`（内容级转写锁 + 归属复用，同一内容只 ASR 一次）。`@Async` 一次性任务无 MQ 消费层，失败只落 `FAILED` + 受控文案，不上抛不重试。

## 新增架构组件

- **统一响应体** `Result<T>` (code/message/data) + **`ErrorCode`**（含 `httpStatus` 显式映射）+ **`ApiExceptionHandler`** (@RestControllerAdvice)
- **状态字段化**：`AiStatus` 枚举（替代「状态混在文案里」）；`AiAnalysisException(retryable)` 异常分层；失败台账 `FailedAnalysisTask`
- **MD5 内容身份化**：`AnalysisTaskKeys`（key 集中 + `normalizeContentHash` 标准化回退 `media-{id}` + `isRealMd5` 判断）；`MediaService.contentHash(mediaId)`（Redis 缓存 `media:md5:{mediaId}` → DB `fileMd5` → 标准化）；归属复用（`analysis:completed-owner` / `analysis:context-owner`，7 天 TTL）过期后回退 DB 按 `file_md5` 反查（`idx_file_md5` 普通索引）实现持久复用
- **双层限流**：`RateLimitService`（AI 5/30、提取 10/60，区分真超限 429 与 Redis 异常 503）
- **Redisson 3.52.0**（原 3.23.5 与 Spring Boot 3.5.x 不兼容导致 StackOverflowError）
- **锁嵌套顺序**：固定 `lock:analysis:{contentHash}` → `lock:analysis-context:{contentHash}`；`asyncTranscribe` 仅拿 contextLock，无反向路径，不构成死锁
- **幂等键生命周期**：`analysis:active:{contentHash}` 失败回滚删除、成功靠 30s TTL 自然过期（避免误删他人重设的键），重复提交由前置 `aiStatus` 校验 + 幂等键双重吞掉（均返回成功，前端轮询等待结果）
- **补偿式重试**：`AnalysisCompensationScheduler`（`@Scheduled` 每分钟扫 `ai_status IN (PENDING,PROCESSING) AND ai_process_at < now()-20min` 的卡死记录，`ai_attempts` 达 3 落 FAILED，否则重新触发 `asyncAnalyze`）；`media_files` 新增 `ai_process_at`/`ai_attempts`；`aiTaskExecutor` 拒绝策略改 `AbortPolicy`（防 CallerRuns 回退监听线程）

## 已知陷阱

- **密码明文**：`UserController` 直接比对明文密码。
- **API 密钥明文** 在 [application.properties](server/src/main/resources/application.properties) 中已提交 Git。
- **MinIO 分片生命周期**需在控制台手动配置：`http://127.0.0.1:9001` → Buckets → media → Lifecycle → Prefix `chunks/`, Expiry 2 days
- **@RequestBody 反序列化**：因 fastjson2 对静态内部类存在兼容性问题，ChunkController 使用 `Map<String, Object>` 接收 JSON 后手动提取字段
- **AI 分析死信兜底**：已增设 `VideoAnalysisDlqConsumer`（监听 `%DLQ%video-group`、独立 consumerGroup `video-group-dlq`）兜底落 `FAILED`（`AiService.markFailedFinal`）。补偿式重试落地后 MQ 重投基本退出主流程，此消费者仅作消息异常的防御性兜底
