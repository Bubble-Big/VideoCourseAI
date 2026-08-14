# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目

VideoCourseAI — 视频上传（整文件 / 分片续传）→ MinIO composeObject 合并 → 提取音频 → ASR 语音转文字 → DeepSeek 智能总结 → Markdown 报告。
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

### 视频上传（两套方案共存）
- **小文件 (< 5MB)**：`POST /media/upload` → MultipartFile → MinIO 直传 → 写 DB → 返回
- **大文件 (≥ 5MB)**：`POST /media/api/chunk/init` → (5MB 切片 × N) `POST /media/api/chunk/upload` → `POST /media/api/chunk/merge` → MinIO composeObject 服务端合并 → 计算全文件 MD5 → 写 DB → 清理分片
- 分片上传关键 Redis Key：`upload:meta:{uploadId}` (Hash, 48h TTL)、`upload:chunks:{uploadId}` (Set, 已完成序号)
- 合并使用 Redisson 分布式锁 `lock:merge:{uploadId}` (看门狗自动续期)，幂等检查防止重复合并
- 前端并发 3 片上传，每片 3 次指数退避重试，localStorage 持久化 uploadId 支持页面刷新后恢复

### AI 异步分析
`GET /debug/ai?id={id}` → Redisson 分布式锁 `lock:analyze:{id}` → Redis 令牌桶限流 (10次/分钟) → 写 `AnalysisTaskMsg` 到 RocketMQ topic `video-analysis-topic` → 立即返回。
`VideoAnalysisConsumer` 收到消息 → `CompletableFuture.runAsync()` 提交到 `aiTaskExecutor` (核心4/最大8/队列100) → `AiService.asyncAnalyze()` → `AliyunDeepSeekStrategy`：FFmpeg 提取 MP3 (15min) → ASR (3次重试, 5xx 等 2s) → DeepSeek (3次重试, 5xx 等 2s) → 写 DB → 删 Redis 缓存 `media:list:user:{userId}`。
前端 3s 轮询 `GET /media/list`，按 `aiStatus` 字段（SUCCESS/FAILED）判断是否完成。

## 新增架构组件

- **统一响应体** `Result<T>` (code/message/data)，`ErrorCode` 枚举集中管理错误码
- **全局异常处理** `ApiExceptionHandler` (@RestControllerAdvice)，Controller 无需 try-catch
- **Redisson 3.52.0**（原 3.23.5 与 Spring Boot 3.5.x 不兼容导致 StackOverflowError）

## 已知陷阱

- **密码明文**：`UserController` 直接比对明文密码。
- **API 密钥明文** 在 [application.properties](server/src/main/resources/application.properties) 中已提交 Git。
- **MinIO 分片生命周期**需在控制台手动配置：`http://127.0.0.1:9001` → Buckets → media → Lifecycle → Prefix `chunks/`, Expiry 2 days
- **@RequestBody 反序列化**：因 fastjson2 对静态内部类存在兼容性问题，ChunkController 使用 `Map<String, Object>` 接收 JSON 后手动提取字段
