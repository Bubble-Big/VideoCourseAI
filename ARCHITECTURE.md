# VideoCourseAI — 智能视频内容理解平台 架构分析文档

> 分析日期：2026-08-15  
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
| **AI 服务** | SiliconFlow API（DeepSeek-V3.2 + TeleAI/TeleSpeechASR） | — |
| **AI SDK（遗留）** | DashScope SDK (阿里云) | 2.16.0 |
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
│       │       ├── V1__add_file_size_and_md5.sql     # 分片上传字段迁移
│       │       ├── V2__add_ai_status.sql             # AI/转写状态字段迁移
│       │       └── V3__add_failed_analysis_task.sql  # 失败台账建表
│       └── java/com/example/server/
│           ├── ServerApplication.java   # 启动类
│           ├── common/                  # 公共组件 (新增)
│           │   ├── Result.java          # 统一 API 响应体
│           │   ├── ErrorCode.java       # 统一错误码枚举
│           │   └── AiStatus.java        # AI 分析/文字提取状态枚举 (新增)
│           ├── config/                  # 配置层
│           │   ├── MinioConfig.java     # MinIO 客户端配置 (含分片生命周期)
│           │   ├── ThreadPoolConfig.java# 线程池配置
│           │   └── WebConfig.java       # 跨域 CORS 配置
│           ├── controller/              # 控制层
│           │   ├── UserController.java  # 用户注册/登录
│           │   ├── MediaController.java # URL 上传/列表/删除
│           │   ├── ChunkController.java # 分片上传 (新增)
│           │   ├── DebugController.java # AI分析/转写/音频下载
│           │   └── ApiExceptionHandler.java # 全局异常处理 (新增)
│           ├── service/                 # 服务层
│           │   ├── MediaService.java    # 媒体处理服务 (+contentHash MD5 指纹)
│           │   ├── ChunkUploadService.java  # 分片上传核心逻辑 (新增)
│           │   ├── AiService.java       # AI 分析服务 (状态机 + 内容复用)
│           │   ├── RateLimitService.java   # 双层令牌桶限流 (新增)
│           │   └── FailedAnalysisTaskService.java # 失败台账服务 (新增)
│           ├── consumer/                # MQ 消费者
│           │   └── VideoAnalysisConsumer.java
│           ├── strategy/                # 策略模式
│           │   ├── AiAnalysisStrategy.java        # 策略接口
│           │   └── impl/AliyunDeepSeekStrategy.java # FFmpeg + ASR + DeepSeek 实现
│           ├── dto/                     # 数据传输对象
│           │   ├── AnalysisTaskMsg.java # MQ 消息体
│           │   └── ChunkUploadDTO.java  # 分片上传请求/响应 DTO (新增)
│           ├── entity/                  # 实体层
│           │   ├── User.java
│           │   ├── MediaFile.java       # (+file_size/file_md5 +ai_status/transcript_status)
│           │   └── FailedAnalysisTask.java # AI 失败台账实体 (新增)
│           ├── exception/               # 异常定义 (新增)
│           │   ├── BusinessException.java
│           │   └── AiAnalysisException.java # 带 retryable 标志的 AI 异常 (新增)
│           ├── mapper/                  # 数据访问层
│           │   ├── UserMapper.java
│           │   ├── MediaFileMapper.java
│           │   └── FailedAnalysisTaskMapper.java # 台账 DAO (新增)
│           └── utils/                   # 工具类
│               ├── FfmpegUtils.java     # FFmpeg 音频提取（统一入口）
│               ├── MinioUtils.java      # MinIO 上传/删除/分片/流式拷贝
│               ├── YtDlpUtils.java      # yt-dlp 视频下载
│               ├── DeepSeekUtils.java   # DeepSeek AI 调用
│               ├── AliyunAsrUtils.java  # 语音识别 (SiliconFlow TeleSpeechASR)
│               └── AnalysisTaskKeys.java # 分析任务 Key + contentHash 标准化 (新增)
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
│   │ 用户登录  │  │ 本地上传 │  │ URL下载   │ │ AI分析/文字提取   │   │
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
│  │  /user/register    /media/upload-url    /media/api/chunk/init │   │
│  │  /user/login       /media/list          /media/api/chunk/check│   │
│  │                    /media/delete        /media/api/chunk/upload│  │
│  │                                         /media/api/chunk/merge│  │
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
    │ media_db │       │缓存/锁/限流/身份化│   │  Broker:10911   │
    └──────────┘       └──────────────┘    └────────┬────────┘
                                                    │ 消费消息
    ┌──────────┐                           ┌────────▼────────┐
    │  MinIO   │                           │ VideoAnalysis   │
    │ :9000    │◄──── 文件上传 ────────────│ Consumer        │
    │ 对象存储  │                           │ (同步消费+内容级锁│
    └──────────┘                           │  + 失败台账)     │
                                           └────────┬────────┘
                                                    │
                                           ┌────────▼────────┐
                                           │   AiService     │
                                           │ asyncAnalyze()  │
                                           │ 状态机+内容复用  │
                                           └────────┬────────┘
                                                    │
                                    ┌───────────────┼───────────────┐
                                    ▼                               ▼
                           ┌──────────────┐                ┌──────────────┐
                           │FFmpeg 提取音频│                │ Aliyun ASR   │
                           │(本地进程调用) │                │ 语音转文字    │
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

所有文件（含小文件）统一走分片上传，小于 5MB 的文件只有 1 片。

```
用户选择文件 ──► 统一走分片上传
                    │
                    ▼
          POST /media/api/chunk/init
          {fileName, fileSize, totalChunks, userId, force}
                    │
                    ▼
          去重检测(force=false) → Redis meta Hash → 返回 uploadId
                    │
                    ▼
          POST /media/api/chunk/upload × N
          (并发 3 片, 每片 5MB, 3 次指数退避重试)
                    │
                    ▼
          "先落盘后记账":
          ① 校验 uploadId / chunkIndex
          ② MinIO: chunks/{uploadId}/{idx}
          ③ Redis: SADD upload:chunks:{uploadId}
                    │
                    ▼
          全部完成 → POST /media/api/chunk/merge
                    │
                    ▼
          Redisson 分布式锁 lock:merge:{uploadId}
                    │
                    ▼
          本地合并: 逐分片 copyObjectTo 下载到本地临时文件
          (DigestOutputStream 边写边算全文件 MD5)
                    │
                    ▼
          uploadLocalFile 回传 MinIO
                    │
                    ▼
          ┌─────────┴─────────┐
          ▼ (force 上传)      ▼ (普通上传)
     MD5 比对同名文件      写 DB → 清理分片
      ├相同: 删新+更新旧时间
      └不同: 文件名加 (1)(2) 后缀
                    │
                    ▼
  列表刷新 ←──── Redis 缓存清除
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
- `ChunkUploadService.java` — 分片上传核心业务逻辑（含本地合并/去重/后缀生成）
- `MinioUtils.java` — MinIO 分片/流式拷贝(copyObjectTo)/清理方法
- `client/src/composables/useChunkedUpload.js` — 前端分片上传组合式函数（含文件指纹匹配）
- `MediaController.java` — URL 上传（补算 MD5 去重）/ 列表查询与缓存
- `MediaController.java:53-118` — URL 上传（yt-dlp 下载 → 算 MD5 → 去重 → 入库）

### 5.2 AI 异步分析流程 (核心链路)

```
前端点击 "AI智能总结" ──► GET /debug/ai?id={mediaId}
                              │
                              ▼
                    ┌─ 校验 aiStatus ───────────┐
                    │ PENDING/PROCESSING         │
                    │ → 幂等返回成功（不重复投递） │
                    └────────┬──────────────────┘
                             │ 非运行中
                             ▼
                    ┌─ 提交侧幂等键 ────────────┐
                    │ setIfAbsent(analysis:      │
                    │   active:{contentHash})    │
                    │ (30s TTL, 抢不到→返回成功)  │
                    └────────┬──────────────────┘
                             │ 获取成功
                             ▼
                    ┌─ 双层令牌桶限流 ──────────┐
                    │ 用户级 5次/分 + 全局 30次/分│
                    │ (真超限 429 / Redis 异常 503)│
                    └────────┬──────────────────┘
                             │ 获取令牌成功
                             ▼
                    ┌─ 置 PENDING ─────────────┐
                    │ 发送 AnalysisTaskMsg      │
                    │ (携带 contentHash)        │
                    │ → RocketMQ                │
                    └────────┬──────────────────┘
                             │ 接口立即返回 ✅
                             ▼
          ┌─────────────────────────────────────┐
          │     VideoAnalysisConsumer           │
          │     同步消费（异常上抛触发重投）      │
          │  tryLock(lock:analysis:{contentHash})│
          │  抢不到 → 静默 ACK 跳过              │
          └────────────────┬────────────────────┘
                           │
                           ▼
          ┌─────────────────────────────────────┐
          │        AiService.asyncAnalyze()      │
          │  0. 结果复用(completed-owner)         │
          │  1. 转写(transcribeWithReuse)         │
          │     内容级锁 + 归属复用               │
          │  2. DeepSeek 总结(generateSummaryFromText)│
          │  3. 写 aiStatus=SUCCESS / markFailed  │
          │  4. 删 Redis 用户列表缓存             │
          └────────────────┬────────────────────┘
                           │ 异常上抛 → 消费层决策
                    ┌──────┴────────┐
                    ▼               ▼
              retryable=false   retryable=true
              写台账 + ACK     上抛 → RocketMQ 重投
              (maxReconsumeTimes=2)
                           │
                           ▼
              前端 3秒轮询 aiStatus ──► 侧边栏展示 Markdown
```

**状态流转**（枚举 `AiStatus`，独立字段替代文案判断）：

```
AI 分析：  NONE → PENDING → PROCESSING → SUCCESS / FAILED
                (投递MQ)    (消费者接单)    (结果落库)

文字提取： NONE → PROCESSING → SUCCESS / FAILED
                (提交线程池)     (结果落库)
```

**异常分层**（沿调用链逐层上抛，每层只做该做的）：

| 层 | 组件 | 职责 |
|----|------|------|
| L1 工具层 | `DeepSeekUtils` / `AliyunAsrUtils` | 模型级 3 次重试 + 语义化抛 `AiAnalysisException(retryable)` |
| L2 策略层 | `AliyunDeepSeekStrategy` | 编排 FFmpeg/ASR/DeepSeek，异常透传 |
| L3 服务层 | `AiService` | 状态机落库（SUCCESS/markFailed）+ 异常继续上抛 |
| L4 消费层 | `VideoAnalysisConsumer` | 最终决策：永久失败 → 台账 + ACK；瞬时失败 → 上抛重投 |
| L5 Controller | `DebugController` | 统一 `Result` + `BusinessException` |

**关键文件**：
- `DebugController.java:60-101` — AI 分析入口（幂等键 + 双层限流 + 发 MQ）
- `VideoAnalysisConsumer.java:35-72` — MQ 消费者（内容级锁 + 异常决策）
- `AiService.java:52-105` — 异步分析核心逻辑（状态机 + 结果/转写复用）
- `AiService.java:154-179` — 转写复用 `transcribeWithReuse`（内容级锁 + 归属复用）
- `AliyunDeepSeekStrategy.java:29-74` — FFmpeg + ASR + 总结策略实现
- `RateLimitService.java:35-65` — 双层令牌桶限流
- `AnalysisTaskKeys.java` — 分析任务 Key 定义 + contentHash 标准化

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
| status | VARCHAR | 上传状态 (UPLOADED/COMPLETED) |
| file_path | VARCHAR | MinIO 文件 URL |
| file_size | BIGINT | 文件大小(字节) — 分片上传重构新增 |
| file_md5 | VARCHAR(32) | 全文件 MD5 = 内容指纹 contentHash — 分片上传重构新增 |
| ai_status | VARCHAR(32) | AI 分析状态: NONE/PENDING/PROCESSING/SUCCESS/FAILED — 状态字段化新增 |
| ai_summary | TEXT | AI 总结内容 (Markdown) |
| transcript_status | VARCHAR(32) | 文字提取状态: NONE/PROCESSING/SUCCESS/FAILED — 状态字段化新增 |
| transcript_text | TEXT | 语音转写全文 |
| cover_url | VARCHAR | 封面 URL |
| upload_time | DATETIME | 上传时间 (DB 自动填充) |

**failed_analysis_task 表** (`FailedAnalysisTask.java`) — AI 分析失败台账
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT (自增) | 主键 |
| media_id | BIGINT | 关联 media_files.id |
| error_type | VARCHAR | 异常类型（AiAnalysisException/Exception 等） |
| error_msg | VARCHAR(2000) | 错误摘要（受控，不含堆栈） |
| attempts | INT | 累计投递次数 |
| created_at | DATETIME | 首次失败时间 |

### 6.2 Redis 缓存键设计

| 缓存键 | 类型 | TTL | 说明 |
|--------|------|-----|------|
| `media:list:user:{userId}` | String (JSON) | 30 分钟 | 用户媒体列表缓存 |
| `media:md5:{mediaId}` | String | 7 天 | contentHash 缓存（免查库，`MediaService.contentHash`） |
| `upload:meta:{uploadId}` | Hash | 48 小时 | 分片上传元数据 (fileName, fileSize, totalChunks, userId, status, forceUpload, createdAt, mediaId) |
| `upload:chunks:{uploadId}` | Set | 48 小时 | 已完成分片序号集合 |
| `lock:merge:{uploadId}` | Redisson RLock | WatchDog | 分片合并分布式锁（按会话） |
| `analysis:active:{contentHash}` | String (SET NX) | 30s | 提交侧幂等键，抢不到→返回成功，失败回滚 |
| `lock:analysis:{contentHash}` | Redisson RLock | WatchDog | 消费侧内容级分析锁 |
| `lock:analysis-context:{contentHash}` | Redisson RLock | WatchDog | 内容级转写锁 |
| `analysis:context-owner:{contentHash}` | String | 7 天 | 转写结果归属（跨 mediaId 复用转写文本） |
| `analysis:completed-owner:{contentHash}` | String | 7 天 | 分析结果归属（跨 mediaId 复用 summary） |
| `limit:ai:user:{userId}` / `limit:ai:global` | RRateLimiter | — | AI 分析双层限流（用户/全局 |
| `limit:transcribe:user:{userId}` / `limit:transcribe:global` | RRateLimiter | — | 文字提取双层限流（用户 10/分 + 全局 60/分） |

### 6.3 RocketMQ 消息

**Topic**: `video-analysis-topic`  
**ConsumerGroup**: `video-group`  
**重试**: `maxReconsumeTimes=2`（2 次重投 = 最多 3 次投递，耗尽进默认 `%DLQ%`）  
**消息体** (`AnalysisTaskMsg.java`):
```java
{
  "mediaId": Long,       // 媒体文件 ID
  "action": String,      // 动作类型 (START_ANALYSIS)
  "contentHash": String  // 内容指纹（MD5 标准化），供消费侧内容级锁/幂等/复用
}
```

---

## 七、设计模式与架构决策

### 7.1 策略模式 (Strategy Pattern)

```
AiAnalysisStrategy (接口)
    │
    └── AliyunDeepSeekStrategy (实现, @Component("defaultAiStrategy"))
            ├── transcribe(videoPath)          → FFmpeg 提取音频 → ASR (SiliconFlow TeleSpeechASR)
            ├── generateSummary(videoPath)     → transcribe() → DeepSeek 总结
            └── generateSummaryFromText(text)  → 复用已转写文本直接总结（避免重复 ASR）
```

- **优势**：可通过 `@Qualifier` 切换不同的 AI 实现（如替换为 OpenAI、文心一言等）
- **`generateSummaryFromText`**：配合内容级转写复用，同一内容只真正 ASR 一次，后续只做 LLM 总结
- **文件**：`AiAnalysisStrategy.java`, `AliyunDeepSeekStrategy.java`

### 7.2 生产者-消费者模式 (Producer-Consumer)

```
DebugController (Producer) ──RocketMQ──► VideoAnalysisConsumer (Consumer)
                                               │
                                          同步消费
                                               │
                                      AiService.asyncAnalyze()
```

- **解耦**：Controller 发送消息后立即返回，耗时分析异步进行
- **削峰填谷**：MQ 缓冲任务，消费侧内容级锁串行（同一内容只跑一次）
- **同步消费**：异常在 `onMessage` 内上抛，交给 RocketMQ 按 `maxReconsumeTimes=2` 重投（替代原 `CompletableFuture.runAsync` 立即 ACK 吞异常）
- **文件**：`DebugController.java`, `VideoAnalysisConsumer.java`

> 注：`aiTaskExecutor` 线程池（核心4/最大8/队列100）现仅用于 `@Async` 的文字提取 `asyncTranscribe`，不再承接 MQ 消费。

### 7.3 缓存策略 (Cache-Aside)

```
读: Redis 缓存 → 命中则返回 → 未命中则查 MySQL → 写入 Redis (30分钟TTL)
写: 更新 MySQL → 删除 Redis 缓存 → 下次读取时重新加载
```

- **文件**：`MediaController.java:139-170` (列表查询缓存), `AiService.java:47-59` (分析完成后清除缓存)

### 7.4 分布式锁 (Redisson + WatchDog)

```
提交侧：setIfAbsent("analysis:active:" + contentHash, 30s)   // 幂等键，非 RLock
消费侧：tryLock("lock:analysis:" + contentHash)               // 内容级锁，看门狗
转写侧：tryLock("lock:analysis-context:" + contentHash)       // 内容级转写锁
```

- **身份 = contentHash**：锁以内容指纹为身份，而非 mediaId，实现跨 mediaId / 跨用户的串行化（换 mediaId 重复上传也被拦截）
- **提交侧用幂等键**：秒级 TTL + 失败回滚，替代原 mediaId RLock（防重复点击）
- **WatchDog 机制**：长耗时任务（AI 调用可达数分钟）自动续期，防止锁过期释放
- **锁嵌套顺序**：`lock:analysis` → `lock:analysis-context`，`asyncTranscribe` 仅拿 contextLock，无反向路径，不构成死锁
- **文件**：`DebugController.java:66-72`, `VideoAnalysisConsumer.java:41-47`, `AiService.java:154-179`

### 7.5 令牌桶限流 (双层)

```
AI 分析：  limit:ai:user:{userId} (5/分)  + limit:ai:global (30/分)
文字提取： limit:transcribe:user:{userId} (10/分) + limit:transcribe:global (60/分)
```

- **双层**：用户级 + 全局级，防止单用户刷爆配额 + 整体费用爆炸
- **真超限 vs 异常**：真超限抛 `RATE_LIMITED`(429)；Redis 异常抛 `SERVICE_UNAVAILABLE`(503)，由 `ApiExceptionHandler` 按 `ErrorCode.httpStatus` 映射
- **限流在提交侧（准入）**，锁在消费侧（执行互斥），身份统一为 contentHash
- **文件**：`RateLimitService.java`

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
| `BusinessException` | 动态映射 | 携带 ErrorCode 语义（含 RATE_LIMITED→429、SERVICE_UNAVAILABLE→503） |
| `MissingServletRequestParameterException` / `MethodArgumentTypeMismatchException` / `HttpMessageNotReadableException` | 400 | 参数缺失 / 类型不匹配 / 请求体不可读 |
| `IllegalArgumentException` | 400 | 参数不合法 |
| `IllegalStateException` | 409 | 状态冲突（如重复合并） |
| `NoSuchElementException` | 404 | 资源不存在 |
| `SecurityException` | 403 | 权限不足 |
| `Exception` (兜底) | 500 | 未知异常不泄漏技术细节 |

Controller 层无需 try-catch，专注业务逻辑。`ErrorCode` 通过 `httpStatus` 字段显式映射，避免 `HttpStatus.resolve(code)` 的数值巧合依赖。

**文件**：`controller/ApiExceptionHandler.java`, `exception/BusinessException.java`, `common/ErrorCode.java`

### 7.8 指数退避重试

```
ASR / DeepSeek: 最多 3 次重试, 遇 5xx/408/429 等待 2 秒后重试
4xx 客户端错误: 直接抛 AiAnalysisException(retryable=false) 不重试
```

- **分层重试**：工具层做模型级 3 次重试，耗尽后抛 `AiAnalysisException(retryable=true)`，由消费层决定是否再走 MQ 重投
- **文件**：`AliyunAsrUtils.java:40-102`, `DeepSeekUtils.java:129-178`

### 7.9 内容身份化与结果复用 (MD5)

```
contentHash = normalizeContentHash(mediaId, fileMd5)   // 合法 MD5 小写；非法回退 media-{id}
```

- **身份统一**：锁、幂等、复用全链路以 contentHash 为身份，视频身份 = 内容指纹而非自增 id
- **直传补算**：直传 / URL 上传前算 MD5 写 `fileMd5`（分片合并已有）；`MediaService.contentHash` 统一获取（Redis 缓存 `media:md5:{mediaId}` → DB → 标准化）
- **转写复用**：`analysis:context-owner:{contentHash}` 记录归属，同一内容跨 mediaId 只 ASR 一次
- **结果复用**：`analysis:completed-owner:{contentHash}` 记录归属，同一内容跨 mediaId 只完整分析一次（复制 summary + 转写文本）
- **标准化回退**：历史数据（`fileMd5` 空 / 非 32 位）回退 `media-{id}`，功能不降级
- **文件**：`AnalysisTaskKeys.java`, `MediaService.java:77-87`, `AiService.java:154-275`

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
         │  TeleSpeechASR  │                    │  DeepSeek-V3.2      │
         └─────────────────┘                    └─────────────────────┘
```

**ASR 语音识别**：
- API: `SiliconFlow → TeleAI/TeleSpeechASR`
- 输入: MP3 音频文件 (MultipartFile)
- 输出: 纯文本 transcription
- 重试: 3次，5xx/408/429 等2秒重试，4xx 直接抛 `AiAnalysisException(retryable=false)`

**AI 智能总结**：
- API: `SiliconFlow → DeepSeek-V3.2`
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
- **轮询机制**：3秒间隔轮询后端 `/media/list`，按 `aiStatus` / `transcriptStatus` 状态字段判断（SUCCESS/FAILED 结算，PENDING/PROCESSING 持续转圈）；连续 NONE 未启动约 30s 判定「任务未能启动」，10 分钟兜底判定「任务超时未完成」

### 10.2 前端功能模块

| 功能 | 实现方式 |
|------|---------|
| 用户注册/登录 | 模态框 + localStorage 持久化 |
| 本地上传 | `<input type="file">` + 拖拽 (drag & drop) + 统一分片上传（小文件仅 1 片） |
| 断点续传 | 场景一：内存 File + 续传横幅一键继续；场景二：文件指纹匹配 + 重新选择自动续传 |
| 去重提示 | 内嵌横幅（红色警告）+ 坚持上传走 force 流程 |
| URL 下载 | 输入框 + yt-dlp 后端下载 + 轮询结果 |
| AI 分析 | 按钮触发 RocketMQ → 前端轮询按 `aiStatus` 字段判定完成（`code≠0` 提示限流/锁/冲突错误） |
| 文字提取 | 异步提交 → 轮询按 `transcriptStatus` 字段判定完成 |
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

# AI 服务 (SiliconFlow)
ai.deepseek.api-key=sk-xxx           # SiliconFlow API Key (ASR + 总结共用)
ai.deepseek.base-url=https://api.siliconflow.cn/v1
ai.deepseek.model=deepseek-ai/DeepSeek-V3.2   # 智能总结模型
ai.asr.url=https://api.siliconflow.cn/v1/audio/transcriptions
ai.asr.model=TeleAI/TeleSpeechASR   # 语音识别模型
# ai.aliyun.api-key                 # 遗留配置，代码已不再使用

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
| 限流 | Redis 双层令牌桶 (用户/全局级) | 🟢 低 |
| SQL 注入 | MyBatis Plus 参数化查询 | 🟢 低 |

---

## 十三、可扩展性建议

1. **密码加密**：引入 BCrypt/SCrypt 对用户密码进行哈希存储
2. **认证升级**：使用 JWT + Spring Security 替代简单的 token 机制
3. **配置安全**：API 密钥抽离到环境变量或 Spring Cloud Config / Vault
4. **监控告警**：接入 Prometheus + Grafana 监控 MQ 积压、线程池状态、AI API 调用量
5. **数据库优化**：对 `media_files.user_id` 和 `media_files.status` 建立索引
6. **Function Calling**：README 中提到的基于 Function Calling 的智能问答功能尚未完整实现，可继续完善
7. **AI Provider 扩展**：利用已有的策略模式，增加 OpenAI / 文心一言 / 通义千问等 provider

---

## 十四、文件清单

### 后端 Java 文件 (30+个)

| 文件 | 行数 | 职责 |
|------|------|------|
| `ServerApplication.java` | 18 | Spring Boot 启动类 |
| `common/Result.java` | 32 | 统一 API 响应体 (record) |
| `common/ErrorCode.java` | 37 | 统一错误码枚举（含 httpStatus 显式映射） |
| `common/AiStatus.java` | 22 | AI 分析/文字提取状态枚举 (新增) |
| `config/MinioConfig.java` | 76 | MinIO 客户端初始化 + 桶策略 + 生命周期 |
| `config/ThreadPoolConfig.java` | 35 | AI 任务线程池配置（@Async 文字提取用） |
| `config/WebConfig.java` | 24 | CORS 全局跨域配置 |
| `controller/UserController.java` | 90 | 用户注册/登录 |
| `controller/MediaController.java` | 179 | URL 上传（补算 MD5）/列表/删除 |
| `controller/ChunkController.java` | 103 | 分片上传 5 个端点 (新增) |
| `controller/DebugController.java` | 162 | AI分析（幂等键+限流）/文字提取/音频下载 |
| `controller/ApiExceptionHandler.java` | 105 | 全局异常处理 (新增) |
| `service/MediaService.java` | 99 | 媒体处理服务（+calculateMd5/contentHash） |
| `service/ChunkUploadService.java` | 449 | 分片上传核心逻辑（本地合并） |
| `service/AiService.java` | 300 | 异步 AI 分析（状态机 + 内容复用） |
| `service/RateLimitService.java` | 71 | 双层令牌桶限流 (新增) |
| `service/FailedAnalysisTaskService.java` | 44 | 失败台账服务（record） (新增) |
| `consumer/VideoAnalysisConsumer.java` | 73 | RocketMQ 消费者（同步消费 + 内容级锁） |
| `exception/BusinessException.java` | 20 | 业务异常 (新增) |
| `exception/AiAnalysisException.java` | 27 | 带 retryable 标志的 AI 异常 (新增) |
| `strategy/AiAnalysisStrategy.java` | 26 | AI 分析策略接口 |
| `strategy/impl/AliyunDeepSeekStrategy.java` | 76 | FFmpeg + ASR + DeepSeek 实现 |
| `dto/AnalysisTaskMsg.java` | 30 | RocketMQ 消息体（+contentHash） |
| `dto/ChunkUploadDTO.java` | 116 | 分片上传请求/响应 DTO (新增) |
| `entity/User.java` | 27 | 用户实体 |
| `entity/MediaFile.java` | 35 | 媒体文件实体（+file_size/md5/ai_status/transcript_status） |
| `entity/FailedAnalysisTask.java` | 26 | AI 失败台账实体 (新增) |
| `mapper/UserMapper.java` | 9 | 用户 DAO |
| `mapper/MediaFileMapper.java` | 9 | 媒体文件 DAO |
| `mapper/FailedAnalysisTaskMapper.java` | 9 | 台账 DAO (新增) |
| `utils/MinioUtils.java` | 211 | MinIO 上传/删除/分片/流式拷贝（本地合并） |
| `utils/YtDlpUtils.java` | 88 | yt-dlp 视频下载工具 |
| `utils/DeepSeekUtils.java` | 179 | DeepSeek AI 调用 |
| `utils/AliyunAsrUtils.java` | 103 | 语音识别（SiliconFlow TeleSpeechASR） |
| `utils/AnalysisTaskKeys.java` | 52 | 分析任务 Key + contentHash 标准化 (新增) |
| `utils/FfmpegUtils.java` | 95 | FFmpeg 音频提取 |

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
| `composables/useMedia.js` | 268 | 列表/侧边栏/轮询/删除/下载/转写/AI（按状态字段判断） |
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

1. **全链路异步化**：通过 RocketMQ + 同步消费 + 状态字段化，将长耗时的 AI 分析从主请求链路剥离，失败可重投、可台账
2. **分片上传 + 断点续传**：5MB 固定切片，本地合并（`DigestOutputStream` 边写边算 MD5），Redis 维护上传状态；双场景续传（内存 File 横幅一键继续 + 文件指纹匹配自动恢复）；三层去重（init 轻量提示 + force 坚持上传 MD5 比对 + merge 精确 MD5）
3. **内容身份化**：以 MD5 作为视频内容指纹（contentHash），锁 / 幂等 / 复用全链路以 contentHash 为身份，实现跨 mediaId / 跨用户的串行化与复用
4. **分布式防护**：Redisson 分布式锁 (3.52.0) + 提交侧幂等键 + 双层令牌桶限流（真超限 429 / Redis 异常 503）
5. **面向失败设计**：异常分层（retryable 语义）+ 双层重试（工具层 3 次 + MQ 重投）+ 失败台账 + WatchDog 防锁过期
6. **状态字段化**：`AiStatus` 枚举独立表达状态，前端按字段判断，告别文案 `includes` 猜测
7. **统一 API 规范**：`Result<T>` + `ErrorCode`(httpStatus) + `@RestControllerAdvice` 全局异常处理
8. **策略模式扩展**：AI Provider 通过接口抽象，可灵活替换
9. **容器化部署**：所有中间件 Docker Compose 一键启动

技术栈成熟务实，适合作为 Spring Boot + RocketMQ + AI 集成方向的参考项目。
