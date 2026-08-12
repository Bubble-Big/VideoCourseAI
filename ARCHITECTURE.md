# VideoCourseAI — 智能视频内容理解平台 架构分析文档

> 分析日期：2026-06-24  
> 项目仓库：https://github.com/Bubble-Big/VideoCourseAI

---

## 一、项目概述

**VideoCourseAI** 是一个全链路视频内容理解平台，集成用户鉴权、视频上传（本地/URL）、音频提取、AI 语音转文字与智能总结等能力。项目针对视频处理场景中的 **长耗时阻塞**、**高并发资源冲突**、**大文件传输不稳定** 等痛点，基于 **RocketMQ + Redisson + 分片续传** 重构了系统架构，抛弃了传统的同步处理模式。

**核心标语**：DECODE ALL VIDEOS — 视频解构 · AI赋能

---

## 二、技术栈总览

| 层级 | 技术 | 版本 |
|------|------|------|
| **后端框架** | Spring Boot | 3.5.9 |
| **JDK** | Java | 21 |
| **Web 容器** | Undertow（替换 Tomcat） | — |
| **ORM** | MyBatis Plus | 3.5.9 |
| **数据库** | MySQL | 8.0 |
| **缓存** | Redis | 7.x |
| **分布式锁** | Redisson | 3.52.0 |
| **消息队列** | RocketMQ | 4.9.4 |
| **对象存储** | MinIO | latest |
| **AI SDK** | DashScope SDK (阿里云) | 2.16.0 |
| **HTTP 客户端** | OkHttp | 4.12.0 |
| **JSON** | FastJSON2 | 2.0.43 |
| **前端框架** | Vue 3 | 3.5.24 |
| **构建工具** | Vite (rolldown-vite) | 7.2.5 |
| **Markdown 渲染** | marked | 17.0.1 |
| **HTTP 客户端** | Axios | 1.13.2 |
| **外部工具** | FFmpeg, yt-dlp | latest |
| **容器化** | Docker Compose | v3 |

---

## 三、项目目录结构

```
VideoCourseAI-main/
├── client/                          # Vue 3 前端项目
│   ├── index.html                   # 入口 HTML
│   ├── package.json                 # 前端依赖配置
│   ├── vite.config.js               # Vite 构建配置
│   └── src/
│       ├── main.js                  # Vue 应用入口
│       ├── App.vue                  # 根组件（纯布局组合 + 启动编排，约 30 行）
│       ├── api/
│       │   └── index.js             # 后端接口统一封装（BASE_URL + 全部请求）
│       ├── utils/
│       │   └── format.js            # formatSize / formatTime 格式化工具
│       ├── styles/
│       │   └── main.css             # 全局样式（由原 App.vue <style> 迁移）
│       ├── composables/
│       │   ├── useNotice.js         # 全局通知条（message + showMsg）
│       │   ├── useAuth.js           # 登录态 + 认证弹窗
│       │   ├── useMedia.js          # 列表/侧边栏/轮询/删除/下载/转写/AI
│       │   ├── useUpload.js         # 上传编排（文件/URL/续传/去重/进度）
│       │   ├── useBootstrap.js      # 启动/卸载编排（封装初始化顺序）
│       │   └── useChunkedUpload.js  # 分片上传核心（单例）
│       └── components/
│           ├── AppNavbar.vue        # 导航栏（品牌/登录/状态灯）
│           ├── UploadZone.vue       # 上传区（磁贴/进度条/横幅）
│           ├── VideoList.vue        # 工作台列表
│           ├── ResultSidebar.vue    # AI 总结 / 文字提取侧边栏
│           └── AuthModal.vue        # 登录/注册弹窗
│
├── server/                          # Spring Boot 后端项目
│   ├── pom.xml                      # Maven 依赖配置
│   ├── mvnw / mvnw.cmd              # Maven Wrapper
│   └── src/main/
│       ├── resources/
│       │   ├── application.properties   # 应用配置
│       │   └── db/                      # 数据库脚本
│       │       ├── schema.sql           # 完整建表语句
│       │       └── V1__add_file_size_and_md5.sql  # 增量迁移
│       └── java/com/example/server/
│           ├── ServerApplication.java   # 启动类
│           ├── common/                  # 公共组件 (新增)
│           │   ├── Result.java          # 统一 API 响应体
│           │   └── ErrorCode.java       # 统一错误码枚举
│           ├── config/                  # 配置层
│           │   ├── MinioConfig.java     # MinIO 客户端配置 (含分片生命周期)
│           │   ├── ThreadPoolConfig.java# 线程池配置
│           │   └── WebConfig.java       # 跨域 CORS 配置
│           ├── controller/              # 控制层
│           │   ├── UserController.java  # 用户注册/登录
│           │   ├── MediaController.java # 媒体上传/列表/删除
│           │   ├── ChunkController.java # 分片上传 (新增)
│           │   ├── DebugController.java # AI分析/转写/音频下载
│           │   └── ApiExceptionHandler.java # 全局异常处理 (新增)
│           ├── service/                 # 服务层
│           │   ├── MediaService.java    # 媒体处理服务
│           │   ├── ChunkUploadService.java  # 分片上传核心逻辑 (新增)
│           │   └── AiService.java       # AI 分析服务
│           ├── consumer/                # MQ 消费者
│           │   └── VideoAnalysisConsumer.java
│           ├── strategy/                # 策略模式
│           │   ├── AiAnalysisStrategy.java        # 策略接口
│           │   └── impl/AliyunDeepSeekStrategy.java # 阿里云+DeepSeek实现
│           ├── dto/                     # 数据传输对象
│           │   ├── AnalysisTaskMsg.java # MQ 消息体
│           │   └── ChunkUploadDTO.java  # 分片上传请求/响应 DTO (新增)
│           ├── entity/                  # 实体层
│           │   ├── User.java
│           │   └── MediaFile.java       # (新增 file_size, file_md5 字段)
│           ├── exception/               # 异常定义 (新增)
│           │   └── BusinessException.java
│           ├── mapper/                  # 数据访问层
│           │   ├── UserMapper.java
│           │   └── MediaFileMapper.java
│           └── utils/                   # 工具类
│               ├── FfmpegUtils.java     # FFmpeg 音频提取（统一入口）
│               ├── MinioUtils.java      # MinIO 上传/删除/分片/合并
│               ├── YtDlpUtils.java      # yt-dlp 视频下载
│               ├── DeepSeekUtils.java   # DeepSeek AI 调用
│               └── AliyunAsrUtils.java  # 阿里云语音识别
│
├── rocketmq/
│   └── broker.conf                  # RocketMQ Broker 配置
├── docker-compose.yml               # 中间件一键部署
├── README.md                        # 项目说明
└── LICENSE                          # MIT 许可证
```

---

## 四、系统架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                          FRONTEND (Vue 3)                            │
│                      http://localhost:5173                          │
│   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────────┐   │
│   │ 用户登录  │  │ 本地上传  │  │ URL下载   │  │ AI分析/文字提取  │   │
│   └──────────┘  └──────────┘  └──────────┘  └──────────────────┘   │
└────────────────────────────┬────────────────────────────────────────┘
                             │ HTTP REST (CORS enabled)
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     BACKEND (Spring Boot :9090)                      │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                     CONTROLLER LAYER                          │   │
│  │  UserController    MediaController      ChunkController       │   │
│  │  /user/register    /media/upload        /media/api/chunk/init │   │
│  │  /user/login       /media/upload-url    /media/api/chunk/check│   │
│  │                    /media/list          /media/api/chunk/upload│  │
│  │                    /media/delete        /media/api/chunk/merge│  │
│  │                                         /media/api/chunk/cancel│ │
│  │                    DebugController                            │   │
│  │                    /debug/ai                                  │   │
│  │                    /debug/transcribe                           │   │
│  │                    /debug/download                             │   │
│  └────────┬──────────────────┬───────────────────┬──────────────┘   │
│           │                  │                   │                  │
│  ┌────────▼──────┐  ┌───────▼────────┐  ┌───────▼──────────────┐   │
│  │  UserMapper    │  │ MediaFileMapper│  │  RocketMQTemplate    │   │
│  │  (MyBatis+)    │  │ (MyBatis+)     │  │  (发送分析任务)      │   │
│  └───────┬────────┘  └───────┬────────┘  └───────┬──────────────┘   │
│          │                   │                    │                  │
└──────────┼───────────────────┼────────────────────┼──────────────────┘
           │                   │                    │
           ▼                   ▼                    ▼
    ┌──────────┐       ┌──────────────┐    ┌─────────────────┐
    │  MySQL   │       │    Redis      │    │    RocketMQ     │
    │  :3307   │       │    :6379      │    │  NameServer:9876│
    │ media_db │       │ 缓存/锁/限流   │    │  Broker:10911   │
    └──────────┘       └──────────────┘    └────────┬────────┘
                                                    │ 消费消息
    ┌──────────┐                           ┌────────▼────────┐
    │  MinIO   │                           │ VideoAnalysis   │
    │ :9000    │◄──── 文件上传 ────────────│ Consumer        │
    │ 对象存储  │                           │ (CompletableFuture│
    └──────────┘                           │  + 线程池)       │
                                           └────────┬────────┘
                                                    │
                                           ┌────────▼────────┐
                                           │   AiService     │
                                           │ asyncAnalyze()  │
                                           └────────┬────────┘
                                                    │
                                    ┌───────────────┼───────────────┐
                                    ▼                               ▼
                           ┌──────────────┐                ┌──────────────┐
                           │FFmpeg 提取音频│                │ Aliyun ASR   │
                           │(本地进程调用)  │                │ 语音转文字    │
                           └──────┬───────┘                └──────┬───────┘
                                  │                               │
                                  ▼                               ▼
                           ┌──────────────┐                ┌──────────────┐
                           │  DeepSeek AI │                │ 数据库更新    │
                           │  智能总结     │                │ + 缓存清除    │
                           └──────────────┘                └──────────────┘
```

---

## 五、核心业务流程详解

### 5.1 视频上传流程（分片上传 + 断点续传）

大文件（≥ 5MB）走分片上传链路，小文件（< 5MB）走原有整文件上传。

```
用户选择文件 ──► 前端判断文件大小
                    │
        ┌───────────┴───────────┐
        ▼                       ▼
   < 5MB: 整文件上传        ≥ 5MB: 分片上传
        │                       │
        ▼                       ▼
  POST /media/upload        POST /media/api/chunk/init
  (FormData)                {fileName, fileSize, totalChunks, userId, force}
        │                       │
        ▼                       ▼
  MinIO.putObject()        去重检测(force=false) → Redis meta Hash → 返回 uploadId
        │                       │
        ▼                       ▼
  写 DB → 清缓存          POST /media/api/chunk/upload × N
        │                 (并发 3 片, 每片 5MB, 3 次指数退避重试)
        │                       │
        │                 "先落盘后记账":
        │                 ① 校验 uploadId / chunkIndex
        │                 ② MinIO: chunks/{uploadId}/{idx}
        │                 ③ Redis: SADD upload:chunks:{uploadId}
        │                       │
        │                       ▼
        │                 全部完成 → POST /media/api/chunk/merge
        │                       │
        │                 Redisson 分布式锁 lock:merge:{uploadId}
        │                       │
        │                 MinIO composeObject (服务端合并, 零下载)
        │                       │
        │                 计算全文件 MD5
        │                       │
        │             ┌─────────┴─────────┐
        │             ▼ (force 上传)      ▼ (普通上传)
        │        MD5 比对同名文件      写 DB → 清理分片
        │         ├相同: 删新+更新旧时间
        │         └不同: 文件名加 (1)(2) 后缀
        │                       │
        ▼                       ▼
  列表刷新 ←──── Redis 缓存清除 ────┘
```

**断点续传（两个场景）**：
- **场景一（页面未刷新，File 对象仍在内存）**：上传中断/取消后，前端显示续传横幅，用户点击「继续上传」直接续传，无需重新选择文件。
- **场景二（页面刷新，File 对象已丢失）**：用户重新选择同一文件，前端用文件指纹（`fileName + fileSize + lastModified`）匹配 localStorage 中的 uploadId，调 `/check` 获取已传分片后自动续传。

**去重策略（三层）**：
1. **init 阶段（轻量启发式）**：`(userId, fileName, fileSize)` 查 DB，命中则内嵌横幅提示「资料库中已存在」
2. **坚持上传（force=true）**：跳过 init 去重，正常分片上传；合并后计算 MD5 与疑似重复文件比对
   - MD5 相同 → 同一文件，删除新上传，更新旧记录 `upload_time` 使其排列到列表顶部
   - MD5 不同 → 同名不同文件，文件名自动加 `(1)`、`(2)` 后缀
3. **merge 阶段（精确 MD5）**：全文件 MD5 存入 `media_files.file_md5`，供后续精确去重

**列表排序**：`ORDER BY upload_time DESC`（最新上传排顶部），去重替换时更新旧记录时间即可自然置顶。

**关键文件**：
- `ChunkController.java` — 分片上传 5 个 REST 端点
- `ChunkUploadService.java` — 分片上传核心业务逻辑（含去重/后缀生成）
- `MinioUtils.java` — MinIO 分片/合并/清理方法
- `client/src/composables/useChunkedUpload.js` — 前端分片上传组合式函数（含文件指纹匹配）
- `MediaController.java` — 整文件上传 / 列表排序
- `MediaController.java:92-137` — URL 上传

### 5.2 AI 异步分析流程 (核心链路)

```
前端点击 "AI智能总结" ──► GET /debug/ai?id={mediaId}
                              │
                              ▼
                    ┌─ Redisson 分布式锁 ─┐
                    │ lock:analyze:{id}   │
                    │ (防重复点击)         │
                    └────────┬────────────┘
                             │ 获取锁成功
                             ▼
                    ┌─ Redisson 令牌桶限流 ─┐
                    │ limit:ai:global       │
                    │ (10次/分钟, 防费用爆炸) │
                    └────────┬──────────────┘
                             │ 获取令牌成功
                             ▼
                    ┌─ 更新状态为"排队中" ─┐
                    │ 发送 AnalysisTaskMsg  │
                    │ → RocketMQ            │
                    │ topic: video-analysis │
                    └────────┬──────────────┘
                             │ 接口立即返回 ✅
                             ▼
          ┌─────────────────────────────────────┐
          │     VideoAnalysisConsumer           │
          │     (RocketMQ 消费者)                │
          │     onMessage() → CompletableFuture  │
          │     → 提交到 aiTaskExecutor 线程池   │
          └────────────────┬────────────────────┘
                           │
                           ▼
          ┌─────────────────────────────────────┐
          │           AiService.asyncAnalyze()   │
          │                                      │
          │  1. Ffmpeg 提取音频 (extractAudio)   │
          │  2. Aliyun ASR 语音转文字            │
          │     (3次指数退避重试, 2s间隔)         │
          │  3. DeepSeek AI 智能总结             │
          │     (R1-Distill-Qwen-32B 模型)       │
          │  4. 数据库更新 (aiSummary,            │
          │     transcriptText)                  │
          │  5. 删除 Redis 用户列表缓存           │
          └─────────────────────────────────────┘
                           │
                           ▼
              前端 3秒轮询检测 ──► 发现结果 ──► 侧边栏展示 Markdown
```

**关键文件**：
- `DebugController.java:53-103` — AI 分析入口
- `VideoAnalysisConsumer.java:17-57` — MQ 消费者
- `AiService.java:27-71` — 异步分析核心逻辑
- `AliyunDeepSeekStrategy.java:15-109` — FFmpeg + ASR + AI 策略实现

### 5.3 用户认证流程

```
注册: POST /user/register  ──► 检查用户名唯一 ──► 插入 users 表 ──► 返回用户信息
登录: POST /user/login     ──► 查询用户名+密码  ──► 返回 token + userInfo
前端: localStorage 存储用户信息, 请求时附带 userId
```

**关键文件**：
- `UserController.java:22-59` — 注册接口
- `UserController.java:63-90` — 登录接口
- `User.java` — 用户实体 (id, username, password, nickname, avatar, role)

---

## 六、数据模型设计

### 6.1 数据库表

**users 表** (`User.java`)
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT (自增) | 主键 |
| username | VARCHAR | 用户名 |
| password | VARCHAR | 密码（明文） |
| nickname | VARCHAR | 昵称 |
| avatar | VARCHAR | 头像 URL |
| role | VARCHAR | 角色 (USER) |

**media_files 表** (`MediaFile.java`)
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT (自增) | 主键 |
| user_id | BIGINT | 上传者 ID |
| filename | VARCHAR | 文件名 |
| status | VARCHAR | 状态 (UPLOADED/COMPLETED) |
| file_path | VARCHAR | MinIO 文件 URL |
| file_size | BIGINT | 文件大小(字节) — 分片上传重构新增 |
| file_md5 | VARCHAR(32) | 全文件 MD5(合并后计算) — 分片上传重构新增 |
| ai_summary | TEXT | AI 总结内容 (Markdown) |
| transcript_text | TEXT | 语音转写全文 |
| cover_url | VARCHAR | 封面 URL |
| upload_time | DATETIME | 上传时间 (DB 自动填充) |

### 6.2 Redis 缓存键设计

| 缓存键 | 类型 | TTL | 说明 |
|--------|------|-----|------|
| `media:list:user:{userId}` | String (JSON) | 30 分钟 | 用户媒体列表缓存 |
| `upload:meta:{uploadId}` | Hash | 48 小时 | 分片上传元数据 (fileName, fileSize, totalChunks, userId, status, forceUpload, createdAt) |
| `upload:chunks:{uploadId}` | Set | 48 小时 | 已完成分片序号集合 |
| `lock:merge:{uploadId}` | Redisson RLock | 120s | 分片合并分布式锁 |
| `lock:analyze:{id}` | Redisson RLock | WatchDog | AI 分析任务分布式锁 |
| `limit:ai:global` | RateLimiter | — | 全局 AI 调用限流 (10次/分钟) |

### 6.3 RocketMQ 消息

**Topic**: `video-analysis-topic`  
**ConsumerGroup**: `video-group`  
**消息体** (`AnalysisTaskMsg.java`):
```java
{
  "mediaId": Long,    // 媒体文件 ID
  "action": String    // 动作类型 (START_ANALYSIS)
}
```

---

## 七、设计模式与架构决策

### 7.1 策略模式 (Strategy Pattern)

```
AiAnalysisStrategy (接口)
    │
    └── AliyunDeepSeekStrategy (实现, @Component("defaultAiStrategy"))
            ├── transcribe()      → Ffmpeg 提取音频 → Aliyun ASR
            └── generateSummary() → transcribe() → DeepSeek AI 总结
```

- **优势**：可通过 `@Qualifier` 切换不同的 AI 实现（如替换为 OpenAI、文心一言等）
- **文件**：`AiAnalysisStrategy.java`, `AliyunDeepSeekStrategy.java`

### 7.2 生产者-消费者模式 (Producer-Consumer)

```
MediaController (Producer) ──RocketMQ──► VideoAnalysisConsumer (Consumer)
                                               │
                                        CompletableFuture
                                               │
                                        aiTaskExecutor (线程池)
```

- **解耦**：Controller 发送消息后立即返回（< 50ms），耗时分析异步进行
- **削峰填谷**：MQ 缓冲任务，线程池控制并发（核心4线程，最大8线程，队列100）
- **文件**：`DebugController.java:90-91`, `VideoAnalysisConsumer.java`, `ThreadPoolConfig.java`

### 7.3 缓存策略 (Cache-Aside)

```
读: Redis 缓存 → 命中则返回 → 未命中则查 MySQL → 写入 Redis (30分钟TTL)
写: 更新 MySQL → 删除 Redis 缓存 → 下次读取时重新加载
```

- **文件**：`MediaController.java:139-170` (列表查询缓存), `AiService.java:47-59` (分析完成后清除缓存)

### 7.4 分布式锁 (Redisson + WatchDog)

```
lockKey = "lock:analyze:" + mediaId
tryLock(0, -1, TimeUnit.SECONDS)  // 等待0秒, 自动续期(WatchDog)
```

- **MD5 内容指纹去重**：同一视频的重复分析请求被锁拦截
- **WatchDog 机制**：长耗时任务（AI 调用可达数分钟）自动续期，防止锁过期释放
- **文件**：`DebugController.java:55-62`

### 7.5 令牌桶限流

```
RateType.OVERALL, 10 tokens/minute
```

- 全局每分钟最多 10 次 AI 分析请求，防止 API 费用爆炸
- **文件**：`DebugController.java:65-74`

### 7.6 统一响应体 (Result<T>)

所有 API 统一返回 `Result<T>` JSON 结构：

```json
{ "code": 0, "message": "success", "data": {...} }
```

- `code == 0` 表示成功，非 0 承载业务/系统错误码
- HTTP 状态码仅表达传输层语义，业务语义由 `code` 承载
- `ErrorCode` 枚举集中管理错误码 (400/401/403/404/409/422/500)

**文件**：`common/Result.java`, `common/ErrorCode.java`

### 7.7 全局异常处理 (@RestControllerAdvice)

`ApiExceptionHandler` 统一拦截 Controller 层异常，转换为 `Result` 响应：

| 异常类型 | HTTP 状态码 | 说明 |
|---------|------------|------|
| `BusinessException` | 动态映射 | 携带 ErrorCode 语义 |
| `IllegalArgumentException` | 400 | 参数不合法 |
| `IllegalStateException` | 409 | 状态冲突（如重复合并） |
| `Exception` (兜底) | 500 | 未知异常不泄漏技术细节 |

Controller 层无需 try-catch，专注业务逻辑。

**文件**：`controller/ApiExceptionHandler.java`, `exception/BusinessException.java`

### 7.8 指数退避重试

```
ASR 请求: 最多3次重试, 遇到 5xx 错误等待2秒后重试
AI 请求: OkHttp 超时 5 分钟 (300s readTimeout)
```

- **文件**：`AliyunAsrUtils.java:31-81`

---

## 八、中间件部署架构

所有中间件通过 `docker-compose.yml` 一键部署：

| 服务 | 容器名 | 端口映射 | 说明 |
|------|--------|---------|------|
| MySQL 8.0 | mysql-media | 3307:3306 | 数据库 media_db |
| Redis | redis-media | 6379:6379 | 缓存/分布式锁/限流 |
| MinIO | minio | 9000:9000, 9001:9001 | 对象存储 (API/控制台) |
| RocketMQ NameServer | rmqnamesrv | 9876:9876 | 路由注册中心 |
| RocketMQ Broker | rmqbroker | 10911:10911, 10909:10909 | 消息存储转发 |
| RocketMQ Dashboard | rmqdashboard | 8180:8082 | 可视化控制台 |

所有服务位于同一 Docker 网络 `my-network` (bridge 模式)。

---

## 九、AI 集成链路

```
                            ┌─────────────────────────┐
                            │     SiliconFlow API      │
                            │  (api.siliconflow.cn)    │
                            └──────────┬──────────────┘
                                       │
                  ┌────────────────────┼────────────────────┐
                  │                                        │
         ┌────────▼────────┐                    ┌──────────▼──────────┐
         │  AliyunAsrUtils │                    │   DeepSeekUtils     │
         │                 │                    │                     │
         │  POST /v1/audio/│                    │  POST /v1/chat/     │
         │  transcriptions │                    │  completions        │
         │                 │                    │                     │
         │  Model:         │                    │  Model:             │
         │  TeleAI/        │                    │  deepseek-ai/       │
         │  TeleSpeechASR  │                    │  DeepSeek-R1-       │
         │                 │                    │  Distill-Qwen-32B   │
         └─────────────────┘                    └─────────────────────┘
```

**ASR 语音识别**：
- API: `SiliconFlow → TeleAI/TeleSpeechASR`
- 输入: MP3 音频文件 (MultipartFile)
- 输出: 纯文本 transcription
- 重试: 3次，5xx 错误等2秒重试

**AI 智能总结**：
- API: `SiliconFlow → DeepSeek-R1-Distill-Qwen-32B`
- System Prompt: 设定为"信息架构师"角色
- 输出格式: Markdown（核心摘要 → 深度洞察 → 原始内容精选 → 领域标签）
- 超时: 连接60s, 读取300s

**关键文件**：`DeepSeekUtils.java`, `AliyunAsrUtils.java`

---

## 十、前端架构

### 10.1 技术特点

- **组件化拆分**：前端按功能模块拆分为 5 个组件 + 6 个组合式函数（Composable 单例），`App.vue` 仅负责布局组合与启动编排（约 30 行）
- **赛博朋克风格**：自定义 CSS 变量、SVG 噪点背景、霓虹绿 (#c5f946) 主题色
- **响应式状态**：Vue 3 Composition API (`ref`, `computed`, `watch`, `onMounted`)
- **Markdown 渲染**：`marked` 库解析 AI 返回的总结内容
- **轮询机制**：3秒间隔轮询后端 `/media/list` 检测异步任务完成状态，10分钟强制超时兜底

### 10.2 前端功能模块

| 功能 | 实现方式 |
|------|---------|
| 用户注册/登录 | 模态框 + localStorage 持久化 |
| 本地上传 | `<input type="file">` + 拖拽 (drag & drop) + 分片/整文件双路径 |
| 断点续传 | 场景一：内存 File + 续传横幅一键继续；场景二：文件指纹匹配 + 重新选择自动续传 |
| 去重提示 | 内嵌横幅（红色警告）+ 坚持上传走 force 流程 |
| URL 下载 | 输入框 + yt-dlp 后端下载 + 轮询结果 |
| AI 分析 | 按钮触发 RocketMQ → 前端轮询检测 `##` 标记判定完成 |
| 文字提取 | 异步提交 → 轮询结果 |
| 音频下载 | FFmpeg 转码 MP3 → Blob 下载 |
| 视频删除 | DELETE 请求 + 前端列表移除 |
| 工作台 | 单列横排列表，文件名左、按钮右 |

### 10.3 前端目录结构与状态管理

前端已从单文件（`App.vue` 约 1162 行）重构为按功能模块划分的多文件结构，采用 **Composable 单例** 共享状态（不引入 Pinia）。

**分层职责**：

| 层 | 目录/文件 | 职责 |
|----|-----------|------|
| 视图层 | `components/*.vue`（5 个） | 纯展示 + 事件触发，直接 import composable |
| 状态/逻辑层 | `composables/*.js`（6 个） | 模块级响应式状态 + 业务逻辑，单例共享 |
| 接口层 | `api/index.js` | 统一 `BASE_URL` 与全部后端请求，杜绝 URL 硬编码 |
| 工具层 | `utils/format.js` | 通用格式化函数 |
| 样式层 | `styles/main.css` | 全局样式（非 scoped，原 App.vue `<style>` 迁移） |

**Composable 依赖关系（单向，无循环）**：

```
useNotice / useAuth / useChunkedUpload   ← 无依赖
useUpload  → useChunkedUpload + useNotice + useAuth + useMedia + api
useMedia   → useNotice + useAuth + api + marked
useBootstrap → useAuth + useUpload          （仅编排启动顺序）
```

**首次数据加载**：`App.vue` 在 `onMounted` 调用 `useBootstrap.start()` → `restoreSession()` 恢复登录态 → `useMedia` 中的 `watch(currentUser)` 统一驱动列表刷新（登录刷新 / 登出清空），避免 `useAuth` 与 `useMedia` 形成循环依赖；`onUnmounted` 调用 `stop()` 注销 `beforeunload` 监听。

---

## 十一、配置项说明

### 关键配置 (`application.properties`)

```properties
# 服务端口
server.port=9090

# MySQL (端口 3307 映射到 Docker 3306)
spring.datasource.url=jdbc:mysql://localhost:3307/media_db

# 文件上传限制 (最大 2GB)
spring.servlet.multipart.max-file-size=2048MB

# AI API 密钥
ai.deepseek.api-key=sk-xxx           # SiliconFlow API Key
ai.deepseek.base-url=https://api.siliconflow.cn/v1
ai.aliyun.api-key=sk-xxx            # 阿里云 API Key

# MinIO 对象存储
minio.endpoint=http://localhost:9000
minio.bucketName=media

# 外部工具路径 (Windows)
tool.ytdlp.path=D:/yt-dlp/yt-dlp.exe
tool.ffmpeg.dir=D:/ffmpeg/bin

# RocketMQ
rocketmq.name-server=127.0.0.1:9876
rocketmq.producer.group=video-analysis-group
```

---

## 十二、安全分析

| 维度 | 现状 | 风险等级 |
|------|------|---------|
| 密码存储 | 明文存储 (直接比对) | 🔴 高 |
| 认证机制 | 简单 token (user_{id})，无 JWT | 🟡 中 |
| API 密钥 | 明文写在配置文件中 | 🟡 中 |
| 跨域 | 全局允许所有来源 (`*`) | 🟡 中 |
| 文件上传 | 无文件类型校验 (仅前端过滤) | 🟡 中 |
| 限流 | Redis 令牌桶 (10次/分钟) | 🟢 低 |
| SQL 注入 | MyBatis Plus 参数化查询 | 🟢 低 |

---

## 十三、可扩展性建议

1. **密码加密**：引入 BCrypt/SCrypt 对用户密码进行哈希存储
2. **认证升级**：使用 JWT + Spring Security 替代简单的 token 机制
3. ~~**前端组件化**~~ ✅ 已完成：App.vue 已拆分为 5 个组件 + 6 个组合式函数（详见 10.3 节）；下一步可考虑引入 Pinia / Vue Router 进一步完善状态与路由管理
4. **配置安全**：API 密钥抽离到环境变量或 Spring Cloud Config / Vault
5. **监控告警**：接入 Prometheus + Grafana 监控 MQ 积压、线程池状态、AI API 调用量
6. **数据库优化**：对 `media_files.user_id` 和 `media_files.status` 建立索引
7. **Function Calling**：README 中提到的基于 Function Calling 的智能问答功能尚未完整实现，可继续完善
8. **AI Provider 扩展**：利用已有的策略模式，增加 OpenAI / 文心一言 / 通义千问等 provider

---

## 十四、文件清单

### 后端 Java 文件 (30+个)

| 文件 | 行数 | 职责 |
|------|------|------|
| `ServerApplication.java` | 18 | Spring Boot 启动类 |
| `common/Result.java` | 32 | 统一 API 响应体 (新增) |
| `common/ErrorCode.java` | 30 | 统一错误码枚举 (新增) |
| `config/MinioConfig.java` | 76 | MinIO 客户端初始化 + 桶策略 + 生命周期 |
| `config/ThreadPoolConfig.java` | 35 | AI 任务线程池配置 |
| `config/WebConfig.java` | 24 | CORS 全局跨域配置 |
| `controller/UserController.java` | 90 | 用户注册/登录 |
| `controller/MediaController.java` | 198 | 媒体上传/列表/删除 |
| `controller/ChunkController.java` | 103 | 分片上传 5 个端点 (新增) |
| `controller/DebugController.java` | 150 | AI分析/文字提取/音频下载 |
| `controller/ApiExceptionHandler.java` | 105 | 全局异常处理 (新增) |
| `service/MediaService.java` | 35 | 媒体处理服务 |
| `service/ChunkUploadService.java` | 384 | 分片上传核心逻辑 (新增) |
| `service/AiService.java` | 103 | 异步 AI 分析 + 缓存清除 |
| `consumer/VideoAnalysisConsumer.java` | 57 | RocketMQ 消费者 |
| `exception/BusinessException.java` | 20 | 业务异常 (新增) |
| `strategy/AiAnalysisStrategy.java` | 20 | AI 分析策略接口 |
| `strategy/impl/AliyunDeepSeekStrategy.java` | 71 | ASR + DeepSeek 实现 |
| `dto/AnalysisTaskMsg.java` | 21 | RocketMQ 消息体 |
| `dto/ChunkUploadDTO.java` | 116 | 分片上传请求/响应 DTO (新增) |
| `entity/User.java` | 27 | 用户实体 |
| `entity/MediaFile.java` | 33 | 媒体文件实体 (新增 file_size, file_md5) |
| `mapper/UserMapper.java` | 9 | 用户 DAO |
| `mapper/MediaFileMapper.java` | 9 | 媒体文件 DAO |
| `utils/MinioUtils.java` | 238 | MinIO 上传/删除/分片/合并 |
| `utils/YtDlpUtils.java` | 88 | yt-dlp 视频下载工具 |
| `utils/DeepSeekUtils.java` | 123 | DeepSeek AI 调用 |
| `utils/AliyunAsrUtils.java` | 83 | 阿里云 ASR 语音识别 |
| `utils/FfmpegUtils.java` | 95 | FFmpeg 音频提取（统一入口） |

### 前端文件

| 文件 | 行数 | 职责 |
|------|------|------|
| `App.vue` | 30 | 根组件（布局组合 + 启动编排） |
| `main.js` | 5 | Vue 应用入口 |
| `api/index.js` | 105 | 后端接口统一封装（BASE_URL + 全部请求） |
| `utils/format.js` | 16 | formatSize / formatTime 格式化工具 |
| `styles/main.css` | 274 | 全局样式（原 App.vue `<style>` 迁移） |
| `composables/useChunkedUpload.js` | 399 | 分片上传核心（单例） |
| `composables/useUpload.js` | 297 | 上传编排（文件/URL/续传/去重/进度） |
| `composables/useMedia.js` | 239 | 列表/侧边栏/轮询/删除/下载/转写/AI |
| `composables/useAuth.js` | 103 | 登录态 + 认证弹窗 |
| `composables/useBootstrap.js` | 20 | 启动/卸载编排 |
| `composables/useNotice.js` | 14 | 全局通知条（message + showMsg） |
| `components/UploadZone.vue` | 147 | 上传区（磁贴/进度条/横幅） |
| `components/VideoList.vue` | 64 | 工作台列表 |
| `components/AuthModal.vue` | 41 | 登录/注册弹窗 |
| `components/AppNavbar.vue` | 40 | 导航栏（品牌/登录/状态灯） |
| `components/ResultSidebar.vue` | 30 | AI 总结 / 文字提取侧边栏 |
| `vite.config.js` | 7 | Vite 构建配置 |
| `index.html` | 14 | HTML 入口 |

---

## 十五、总结

VideoCourseAI 是一个设计思路清晰的 **视频 + AI 异步处理平台**，核心亮点在于：

1. **全链路异步化**：通过 RocketMQ + CompletableFuture + 线程池，将长耗时的 AI 分析从主请求链路剥离
2. **分片上传 + 断点续传**：5MB 固定切片，MinIO composeObject 服务端合并，Redis 维护上传状态；双场景续传（内存 File 横幅一键继续 + 文件指纹匹配自动恢复）；三层去重（init 轻量提示 + force 坚持上传 MD5 比对 + merge 精确 MD5）
3. **分布式防护**：Redisson 分布式锁 (3.52.0) 防重复处理/合并 + 令牌桶限流保护 AI API 费用
4. **面向失败设计**：分片 3 次指数退避重试、ASR 3 次重试、WatchDog 防止长任务锁过期、5 分钟轮询超时兜底
5. **统一 API 规范**：Result<T> 响应体 + ErrorCode 错误码枚举 + @RestControllerAdvice 全局异常处理
6. **策略模式扩展**：AI Provider 通过接口抽象，可灵活替换
7. **容器化部署**：所有中间件 Docker Compose 一键启动

技术栈成熟务实，适合作为 Spring Boot + RocketMQ + AI 集成方向的参考项目。
