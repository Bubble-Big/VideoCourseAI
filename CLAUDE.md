# CLAUDE.md

## 项目概述

**VideoCourseAI** — 全链路异步视频内容理解平台。用户可以上传视频（本地文件或 URL），系统提取音频后调用 AI（ASR 语音转文字 + DeepSeek 智能总结），最终生成结构化 Markdown 分析报告。

核心标语：DECODE YOUR VIDEO — 影视重构 · 算力赋能

- 仓库：https://github.com/Bubble-Big/VideoCourseAI
- 许可证：MIT

---

## 技术栈

| 层级 | 技术 | 版本/说明 |
|------|------|-----------|
| 后端框架 | Spring Boot | 3.5.9 |
| JDK | Java 21 | — |
| Web 容器 | Undertow（替换 Tomcat） | — |
| ORM | MyBatis Plus | 3.5.9 |
| 数据库 | MySQL | 8.0（Docker，端口 3307） |
| 缓存/锁/限流 | Redis + Redisson | 7.x / 3.23.5 |
| 消息队列 | RocketMQ | 4.9.4（Docker） |
| 对象存储 | MinIO | latest（Docker，API :9000，控制台 :9001） |
| HTTP 客户端 | OkHttp | 4.12.0 |
| JSON | FastJSON2 | 2.0.43 |
| AI API | SiliconFlow | 代理 ASR + DeepSeek-R1 |
| 前端框架 | Vue 3 | 3.5.24（Composition API，单文件组件） |
| 构建工具 | Vite (rolldown-vite) | 7.2.5 |
| Markdown 渲染 | marked | 17.0.1 |
| 外部工具 | FFmpeg, yt-dlp | 需本地安装 |
| 容器化 | Docker Compose | v3 |

---

### 前置依赖

- JDK 21
- Node.js v22+
- FFmpeg（配置 `tool.ffmpeg.dir`）
- yt-dlp（配置 `tool.ytdlp.path`）
- SiliconFlow API Key（配置 `ai.deepseek.api-key`，注册地址 https://cloud.siliconflow.cn/）

---

## 核心架构模式

### 异步分析链路（全链路异步化）

```
用户点击 "AI 智能总结"
  → GET /debug/ai?id={mediaId}
    → Redisson 分布式锁（防重复点击）
    → Redis 令牌桶限流（10次/分钟，防费用爆炸）
    → 写入 "排队中..." 到 DB，发送消息到 RocketMQ
    → 立即返回 "✅ 任务已投递"（< 50ms）
  → VideoAnalysisConsumer 收到消息
    → CompletableFuture + aiTaskExecutor 线程池
      → AiService.asyncAnalyze()
        → FFmpeg 提取音频（15分钟超时）
        → Aliyun ASR 语音转文字（3次重试，仅对 5xx）
        → DeepSeek AI 智能总结（无重试！）
        → 写入 DB（aiSummary + transcriptText）
        → 删除 Redis 缓存
  → 前端 3 秒轮询检测结果（检测 aiSummary 是否包含 "##"）
  → 侧边栏 Markdown 渲染展示
```

### 策略模式

```
AiAnalysisStrategy (接口)
  ├── transcribe()      语音转文字
  └── generateSummary() AI 智能总结
      └── AliyunDeepSeekStrategy (@Component("defaultAiStrategy"))
            通过 @Qualifier("defaultAiStrategy") 注入，可替换为其他实现
```

### 缓存策略（Cache-Aside）

- **读**：先查 Redis `media:list:user:{userId}` → 命中返回 → 未命中查 MySQL → 写 Redis（TTL 30 分钟）
- **写**：更新 MySQL → 删除 Redis 缓存 → 下次读取重新加载
- 注意：目前前端轮询直接调 `/media/list`，不走 WebSocket 推送

### 分布式锁（Redisson + WatchDog）

- `lock:analyze:{mediaId}`：防止同一视频重复提交分析
- WatchDog 自动续期，确保长耗时 AI 调用（可达数分钟）不会导致锁过期

---

## 数据库表

### media_files（核心表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键自增 |
| user_id | BIGINT | 上传者 ID |
| filename | VARCHAR | 文件名 |
| status | VARCHAR | UPLOADED / COMPLETED |
| file_path | VARCHAR | MinIO 文件 URL |
| ai_summary | TEXT | AI 总结 Markdown（**⚠️ 错误信息也会被存入此字段**） |
| transcript_text | TEXT | 语音转写全文 |
| cover_url | VARCHAR | 封面 URL |
| upload_time | DATETIME | DB 自动填充 |

### users

| 字段 | 说明 |
|------|------|
| id, username, password, nickname, avatar, role | 密码明文存储（安全风险） |

---

## 已知问题 & 常见陷阱

### ⚠️ AI 总结失败时错误信息被存入数据库

`DeepSeekUtils.analyzeContent()` 在 API 返回非 200 时**返回错误字符串**（如 `"❌ AI 请求失败: 403 - ..."`）而非抛异常。该字符串被 `AiService.asyncAnalyze()` 直接写入 `mediaFile.aiSummary` 并持久化到 DB。

后果：前端判断 `aiSummary` 已有内容且不含 "正在"/"任务已"，直接展示错误内容，**用户无法重试**。

### ⚠️ ASR 有重试，但 AI 总结没有重试

- `AliyunAsrUtils`：3 次重试，5xx 等 2 秒重试，`retryOnConnectionFailure(true)`
- `DeepSeekUtils`：**零重试**，无 `retryOnConnectionFailure`，单次失败即返回错误

### ⚠️ 密码明文存储

`UserController` 中登录直接比对明文密码，无哈希。

### ⚠️ 前端轮询判断逻辑

- 成功判定：`aiSummary` 包含 `"##"`（Markdown 标题）
- 失败判定：包含 `"失败"` / `"Error"` / `"超时"` / `"500"`
- 重试阻拦：仅检查是否包含 `"任务已"` 或 `"正在"`，不检查是否包含 `"❌"`

### ⚠️ 前端单文件组件

全部业务逻辑集中在 `App.vue`（~890 行），未做组件拆分。

---

## 常用命令

```bash
# 后端
cd server
mvn clean spring-boot:run              # 启动后端
mvn test                                # 运行测试（目前无测试）

# 前端
cd client
npm install                             # 安装依赖
npm run dev                             # 启动开发服务器

# Docker 中间件
docker-compose up -d                    # 启动所有中间件
docker-compose down                     # 停止所有中间件
docker-compose logs -f <service>        # 查看某服务日志

# 查看 RocketMQ 控制台
# 浏览器打开 http://localhost:8180
```

---

## API 概览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/user/register` | 用户注册 |
| POST | `/user/login` | 用户登录 |
| POST | `/media/upload` | 本地上传（MultipartFile） |
| POST | `/media/upload-url` | URL 下载上传 |
| GET | `/media/list` | 用户媒体列表（Redis 缓存） |
| DELETE | `/media/delete` | 删除媒体 |
| GET | `/debug/ai?id={id}` | 触发 AI 智能总结（→ RocketMQ） |
| GET | `/debug/transcribe?id={id}` | 触发纯文字提取 |
| GET | `/debug/download?id={id}` | 下载提取的 MP3 音频 |
