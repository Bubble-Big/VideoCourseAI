# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目

VideoCourseAI — 视频上传（分片续传）→ 本地合并算 MD5 → 提取音频 → ASR 语音转文字 → DeepSeek 智能总结 → Markdown 报告。
Spring Boot 3.5.9 (Java 21, 端口 9090) + Vue 3 (端口 5173) + Docker 中间件。
详细架构见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 工作流程规则

若项目中存在一键启动、一键停止、后端编译等脚本，优先调用脚本快速进行启动与终止。

**启动项目时：**
- ✅ **优先使用** 调用 `/start-dev` skill
- ❌ **不要**手动逐条执行 docker-compose、mvn、npm 命令

**停止项目时：**
- ✅ **优先使用** 调用 `/start-dev` skill

**验证编译时：**
- ✅ **优先调用** `/compile-server` skill 验证后端编译

**测试视频分析链路时：**
- ✅ **优先调用** `/analyze-video` skill 走完整流程

## 架构文档

详细的技术架构、业务流程、数据模型、中间件配置等信息请查看 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 命令（仅供手动调试）

```bash
# 中间件
docker-compose up -d          # MySQL(:3307), Redis(:6379), MinIO(:9000/:9001), RocketMQ(:9876/:10911)

# 后端
cd server && mvn clean spring-boot:run

# 前端
cd client && npm install && npm run dev
```

## 已知陷阱

- **密码明文**：`UserController` 直接比对明文密码。
- **MinIO 分片生命周期**需在控制台手动配置：`http://127.0.0.1:9001` → Buckets → media → Lifecycle → Prefix `chunks/`, Expiry 2 days
- **@RequestBody 反序列化**：因 fastjson2 对静态内部类存在兼容性问题，ChunkController 使用 `Map<String, Object>` 接收 JSON 后手动提取字段
- **AI 分析死信兜底**：已增设 `VideoAnalysisDlqConsumer`（监听 `%DLQ%video-group`、独立 consumerGroup `video-group-dlq`）兜底落 `FAILED`（`AiService.markFailedFinal`）。补偿式重试落地后 MQ 重投基本退出主流程，此消费者仅作消息异常的防御性兜底
