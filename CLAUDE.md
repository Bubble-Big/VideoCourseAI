# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目

VideoCourseAI — 视频上传 → 提取音频 → ASR 语音转文字 → DeepSeek 智能总结 → Markdown 报告。
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

`GET /debug/ai?id={id}` → Redisson 分布式锁 `lock:analyze:{id}` → Redis 令牌桶限流 (10次/分钟) → 写 `AnalysisTaskMsg` 到 RocketMQ topic `video-analysis-topic` → 立即返回。
`VideoAnalysisConsumer` 收到消息 → `CompletableFuture.runAsync()` 提交到 `aiTaskExecutor` (核心4/最大8/队列100) → `AiService.asyncAnalyze()` → `AliyunDeepSeekStrategy`：FFmpeg 提取 MP3 (15min) → ASR (3次重试, 5xx 等 2s) → DeepSeek (3次重试, 5xx 等 2s) → 写 DB → 删 Redis 缓存 `media:list:user:{userId}`。
前端 3s 轮询 `GET /media/list`，检测 `aiSummary` 含 `##` 即完成。

## 已知陷阱

- **错误字符串被写入 DB**：`DeepSeekUtils.analyzeContent()` 失败时返回 `"❌ AI 请求失败: ..."` 字符串而非抛异常，被直接写入 `aiSummary`，前端无法识别为失败。
- **密码明文**：`UserController` 直接比对明文密码。
- **前端单文件**：全部逻辑在 [App.vue](client/src/App.vue) (~890行)，无路由/Pinia，后端 URL `http://localhost:9090` 硬编码。
- **API 密钥明文** 在 [application.properties](server/src/main/resources/application.properties) 中已提交 Git。
