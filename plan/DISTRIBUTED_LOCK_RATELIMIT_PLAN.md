# 分布式锁 + 令牌桶限流 + MD5 内容身份化对齐改造计划书

> 状态：**待实施**（计划阶段，最后更新 2026-08-14）
> 目标：将 VideoCourseAI 的 Redisson 分布式锁、令牌桶限流、以及 MD5 内容指纹的身份化用法，对齐工程化实现。

---

## 一、背景与目标

VideoCourseAI 在分布式锁、令牌桶限流、以及 MD5 内容指纹的身份化用法方面只保留了「基础骨架」，在正确性、成本护栏与内容身份模型上存在差距：

| 维度 | VideoCourseAI 现状 | 目标标杆                                   |
|------|-------------------|----------------------------------------|
| 分布式锁位置 | 锁在**提交侧**（Controller），窗口毫秒级 | 锁在**消费侧**，持锁到分析完成                      |
| 消费侧防重 | **裸奔**，无锁无幂等 | 消费锁 + completedKey 结果复用                |
| 分片合并锁租约 | 固定 120s（已改看门狗） | 看门狗自动续期                                |
| 文字提取 | 无锁 | 内容级上下文锁                                |
| 限流层级 | 单层（仅全局） | 双层（用户级 + 全局级）                          |
| 限流覆盖 | 仅 `/debug/ai` | 分析 / 路由 / 追问 / 检索全覆盖                   |
| **MD5 身份模型** | **仅分片合并算 MD5，且只用于 force 去重** | **上传即算指纹，锁/幂等/复用全链路以 contentHash 为身份** |

**改造目标**（三条线共 9 项）：

1. 锁放在副作用发生处（消费/执行侧），持锁覆盖完整副作用。
2. 用看门狗（`leaseTime = -1`），不用固定租约。
3. 限流升级为双层（用户级 + 全局级），覆盖所有模型调用入口。
4. 限流失败语义区分「真超限」与「Redis 异常」。
5. **MD5 作为视频内容身份（contentHash），取代 mediaId 用于锁 key、幂等、复用。**

---

## 二、现状分析

### 2.1 分布式锁现状

| # | 场景 | 位置 | 锁 key | 租约 | 问题 |
|---|------|------|--------|------|------|
| 1 | AI 分析锁 | `DebugController.java:63` | `lock:analyze:{id}` | 看门狗 | 提交侧毫秒级窗口，消费侧无锁 |
| 2 | 文字提取 | 无 | — | — | **完全无锁**，`@Async` 裸奔 |
| 3 | 分片合并锁 | `ChunkUploadService.java:214` | `lock:merge:{uploadId}` | 看门狗 ✓ | 已改造完成 |

### 2.2 令牌桶限流现状

| # | 场景 | 位置 | 限流 key | 层级 | 问题 |
|---|------|------|---------|------|------|
| 1 | AI 分析 | `DebugController.java:68-76` | `limit:ai:global` | 单层全局 | 挡不住单用户刷爆 |
| 2 | 文字提取 | 无 | — | — | **完全无限流**，可无限刷 ASR |

### 2.3 MD5 内容指纹现状

| 环节 | 现状 | 问题 |
|------|------|------|
| 分片合并 | 边写边算 MD5，存 `fileMd5`（`ChunkUploadService.java:258-277`） | 只用于 force 去重，算完即闲置 |
| 小文件直传 | `MediaController.upload`（`:55`）/ `upload-url`（`:95`）**不算 MD5** | 大量视频 `fileMd5` 为空 |
| 实体字段 | 有 `fileMd5`（`MediaFile.java:31`），无 contentHash 概念 | 语义可复用但未体系化 |
| 锁 key | 全部基于 `mediaId` | 同一内容换 id 上传被视为不同视频 |

---

## 三、对标分析

### 3.1 锁的对标差异（身份维度）

| 对标 | 目标标杆 | VideoCourseAI | 核心差异 |
|------|-----------|---------------|---------|
| 任务级分析锁 | `lock:analysis:{contentHash}:{goalDigest}`（消费侧，`VideoAnalysisConsumer.java:104`） | `lock:analyze:{id}`（提交侧，`DebugController.java:63`） | 层级错位 + 身份维度：contentHash vs id |
| 内容级上下文锁 | `lock:analysis-context:{contentHash}`（`AiService.java:163`） | 文字提取无锁（`AiService.java:82`） | 缺失；且前者按**内容**复用，跨 mediaId |
| 分片合并锁 | `lock:upload:merge:{uploadId}`（`ChunkUploadService.java:118`） | `lock:merge:{uploadId}`（`ChunkUploadService.java:214`） | 租约已对齐，剩「抢不到锁」语义差异 |

### 3.2 限流的对标差异

| 维度 | 目标标杆 | VideoCourseAI |
|------|-----------|---------------|
| 层级 | 用户级 + 全局级双层 | 单层全局 |
| 位置 | Service 层（`AnalysisDispatchService.requireAiQuota`） | Controller 硬编码 |
| 覆盖 | 分析 + 路由 + 追问 + 检索 | 仅 AI 分析 |
| 失败语义 | `RATE_LIMITED` / `SERVICE_UNAVAILABLE` 分开 | 仅 `RATE_LIMITED` |

### 3.3 MD5 身份模型的对标差异

| 环节 | 目标标杆 | VideoCourseAI |
|------|-----------|---------------|
| 计算时机 | 上传即算（`MediaIngestService` + `MediaService.calculateMd5`） | 仅分片合并算，直传缺失 |
| 存储 | DB `contentHash` + Redis 缓存 `media:md5:{mediaId}` | DB `fileMd5`（无缓存） |
| 获取 | `MediaService.contentHash(mediaId)`（缓存→DB→回填） | 无统一获取入口 |
| 标准化 | `AnalysisTaskKeys.normalizeContentHash`（非法回退 `media-{id}`） | 无 |
| 消息传递 | `AnalysisTaskMsg.contentHash` 贯穿提交→消费 | 无 |
| 用途 | 锁 / 幂等 / 复用 / 归属 全链路 | 仅 force 去重 |

---

## 四、改造方案

### 4.1 MD5 内容身份化

> 理念：视频的「身份」= 内容指纹（MD5），而非数据库自增 id。锁、幂等、复用都基于内容指纹，实现跨 mediaId / 跨用户的串行化与复用。
> 字段策略：**复用现有 `fileMd5` 字段**作为内容指纹，不新增 DB 字段（避免改 schema），文档与注释中明确「`fileMd5` 即内容指纹 contentHash」。

#### 改造 A：直传链路补算 MD5

抽 `MediaService.calculateMd5`（对标 DOVideo `MediaService.calculateMd5`），小文件直传与 URL 上传都补算并写入 `fileMd5`：

```java
// MediaService 新增
public String calculateMd5(MultipartFile file) throws IOException {
    return calculateMd5(file.getInputStream());
}

// MediaController.upload 里补：
String fileMd5 = mediaService.calculateMd5(file);   // 先算指纹
String fileUrl = minioUtils.uploadFile(file);        // 再上传
...
mediaFile.setFileMd5(fileMd5);
```

> 说明：`MultipartFile` 支持重复读流，先算 MD5 再上传两次读流是安全的；如需一次 IO，可后续用 `DigestInputStream` 优化（P2）。

#### 改造 B：统一内容指纹获取

`MediaService` 增加 `contentHash(mediaId)`，查 DB `fileMd5` 并经标准化返回（简化版直接查库，Redis 缓存列为 P2）：

```java
public String contentHash(Long mediaId) {
    MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
    return AnalysisTaskKeys.normalizeContentHash(mediaId, mediaFile == null ? null : mediaFile.getFileMd5());
}
```

#### 改造 C：引入 `AnalysisTaskKeys` 工具类

统一 key 生成 + MD5 标准化 + 非法回退：

```java
public final class AnalysisTaskKeys {

    private static final Pattern MD5_PATTERN = Pattern.compile("[a-fA-F0-9]{32}");

    /** 校验 MD5 格式；非法（历史数据 / 直传改造前上传）回退 media-{id}。 */
    public static String normalizeContentHash(Long mediaId, String md5) {
        if (md5 != null && MD5_PATTERN.matcher(md5).matches()) {
            return md5.toLowerCase(Locale.ROOT);
        }
        return "media-" + mediaId;
    }

    /** 任务级分析锁：内容级，跨 mediaId 串行。 */
    public static String analysisLock(String contentHash) {
        return "lock:analysis:" + contentHash;
    }

    /** 内容级上下文锁（转写）：同一内容只跑一次 ASR。 */
    public static String contextLock(String contentHash) {
        return "lock:analysis-context:" + contentHash;
    }
}
```

#### 改造 D：锁 key 从 mediaId 改为 contentHash（身份化落地）

| 锁 | 改造前 | 改造后 |
|----|--------|--------|
| AI 分析消费锁 | `lock:analyze:{mediaId}` | `lock:analysis:{contentHash}`（`analysisLock`） |
| 文字提取锁 | `lock:transcribe:{mediaId}` | `lock:analysis-context:{contentHash}`（`contextLock`，对标 DOVideo 上下文锁） |

### 4.2 分布式锁改造

#### 改造 1：AI 分析锁下沉到消费侧 + 身份化

`VideoAnalysisConsumer` 注入 `RedissonClient`，在 `onMessage` 加内容级锁持锁到完成：

```java
@Override
public void onMessage(AnalysisTaskMsg msg) {
    Long mediaId = msg.getMediaId();
    String contentHash = AnalysisTaskKeys.normalizeContentHash(mediaId, msg.getContentHash());
    RLock lock = redissonClient.getLock(AnalysisTaskKeys.analysisLock(contentHash));
    if (!lock.tryLock()) {
        // 同一内容已在分析中（并发消费 / 消息重投 / 换 mediaId 重复上传），跳过，正常 ACK
        log.info("分析任务已在执行，跳过重复消息 mediaId={} contentHash={}", mediaId, contentHash);
        return;
    }
    try {
        // 原有逻辑：aiService.asyncAnalyze(mediaId) ...
    } finally {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
```

- 锁用 `tryLock()` 无参（看门狗）；`asyncAnalyze` 同步顺序执行（transcribe + summary），锁能正确持到完成。
- `msg.getContentHash()` 从消息里取（见改造 F）；若未改造消息，可退化为 `mediaService.contentHash(mediaId)` 查库。
- 提交侧 `DebugController` 的 `lock:analyze:{id}` **保留**（快速防连点），真正串行靠消费侧内容级锁。

#### 改造 2：文字提取加内容级锁

`AiService` 注入 `RedissonClient`，给 `asyncTranscribe` 加内容级锁：

```java
@Async("aiTaskExecutor")
public void asyncTranscribe(Long mediaId) {
    String contentHash = mediaService.contentHash(mediaId);   // 内容级
    RLock lock = redissonClient.getLock(AnalysisTaskKeys.contextLock(contentHash));
    if (!lock.tryLock()) {
        log.info("全文提取已在执行，跳过 mediaId={} contentHash={}", mediaId, contentHash);
        return;
    }
    try {
        // 原有逻辑
    } finally {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
```

- `@Async` 线程池里必须 `tryLock()` 不等待（抢不到立即 return），否则占住线程池线程空等。
- 内容级锁的意义：**同一视频（相同 MD5）被不同用户上传、不同 mediaId 记录，也只跑一次 ASR**，这是 id 级锁做不到的。

#### 改造 3：分片合并锁看门狗（✅ 已完成）

`tryLock(0, 120, TimeUnit.SECONDS)` → `tryLock()`，无需再动。分片合并锁按 `uploadId`（上传会话）而非 contentHash，语义正确——合并保护的是「一次上传会话」，不是「内容」。

#### 改造 4：锁 key 抽工具类

已由改造 C 的 `AnalysisTaskKeys` 统一承接，本项并入改造 C，不再单列。

### 4.3 令牌桶限流改造

> 说明：限流是「用户级 + 全局级」准入控制，与**内容**无关，限流 key 保持 `limit:ai:user:{userId}` / `limit:ai:global`，**不涉及 contentHash**。

#### 改造 5：新建 `RateLimitService`（统一双层限流入口）

对标 `AnalysisDispatchService.requireAiQuota` + `tryAcquireQuota`：

```java
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    // AI 分析配额（transcribe + summary 一次完整分析）
    private static final int AI_USER_PER_MINUTE = 5;
    private static final int AI_GLOBAL_PER_MINUTE = 30;
    // 纯文字提取配额（仅 ASR，成本低于完整分析）
    private static final int TRANSCRIBE_USER_PER_MINUTE = 10;
    private static final int TRANSCRIBE_GLOBAL_PER_MINUTE = 60;

    private final RedissonClient redissonClient;

    /** AI 分析配额：用户级 + 全局级双层。超限抛 RATE_LIMITED，Redis 异常抛 SERVICE_UNAVAILABLE。 */
    public void requireAiQuota(Long userId) {
        tryAcquire("limit:ai:user:", "limit:ai:global",
                AI_USER_PER_MINUTE, AI_GLOBAL_PER_MINUTE, userId);
    }

    /** 文字提取配额：用户级 + 全局级双层。 */
    public void requireTranscribeQuota(Long userId) {
        tryAcquire("limit:transcribe:user:", "limit:transcribe:global",
                TRANSCRIBE_USER_PER_MINUTE, TRANSCRIBE_GLOBAL_PER_MINUTE, userId);
    }

    private void tryAcquire(String userKeyPrefix, String globalKey,
                            int userRate, int globalRate, Long userId) {
        try {
            RRateLimiter userLimiter = redissonClient.getRateLimiter(userKeyPrefix + uid(userId));
            userLimiter.trySetRate(RateType.OVERALL, userRate, 1, RateIntervalUnit.MINUTES);
            if (!userLimiter.tryAcquire()) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "AI 请求过于频繁，请稍后再试");
            }
            RRateLimiter globalLimiter = redissonClient.getRateLimiter(globalKey);
            globalLimiter.trySetRate(RateType.OVERALL, globalRate, 1, RateIntervalUnit.MINUTES);
            if (!globalLimiter.tryAcquire()) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "系统繁忙，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e; // 真超限，原样抛出
        } catch (RuntimeException e) {
            log.warn("ai_rate_limiter_unavailable userId={}", userId, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "AI 服务限流器暂不可用，请稍后再试");
        }
    }

    private String uid(Long userId) {
        return userId == null ? "anon" : String.valueOf(userId);
    }
}
```

#### 改造 6：AI 分析接入双层限流（`DebugController.aiAnalyze`）

当前限流在查库之前，但用户级限流需要 `userId`，顺序调整为 **查库 → 限流**：

```java
// 改前：单层全局限流（在查库前）
rateLimiter.trySetRate(RateType.OVERALL, 10, 1, RateIntervalUnit.MINUTES);
if (!rateLimiter.tryAcquire(1)) throw new BusinessException(ErrorCode.RATE_LIMITED, "...");
MediaFile file = mediaFileMapper.selectById(id);

// 改后：先查库拿 userId，再双层限流
MediaFile file = mediaFileMapper.selectById(id);
if (file == null) throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
rateLimitService.requireAiQuota(file.getUserId());
```

同时删除 `DebugController` 里的限流调用与相关 import；`redissonClient` 保留注入（锁仍用）。

#### 改造 7：文字提取接入限流（`DebugController.transcribe`）

```java
MediaFile mediaFile = mediaFileMapper.selectById(id);
if (mediaFile == null) throw new BusinessException(ErrorCode.NOT_FOUND, "找不到文件记录");
if (AiStatus.PROCESSING.name().equals(mediaFile.getTranscriptStatus())) {
    throw new BusinessException(ErrorCode.CONFLICT, "任务已在后台运行，无需重复提交");
}
rateLimitService.requireTranscribeQuota(mediaFile.getUserId());   // 新增限流
```

#### 改造 8：限流失败语义（已内置，无需额外改动）

`RATE_LIMITED(429)` / `SERVICE_UNAVAILABLE(503)` 由 `RateLimitService` 区分抛出，`ApiExceptionHandler` 按 `ErrorCode.httpStatus` 统一映射。

### 4.4 可选进阶（P1 / P2，本次可缓做）

#### 改造 F（P1）：`AnalysisTaskMsg` 携带 contentHash

消息里带 contentHash，消费侧直接用，避免再查库：

```java
// AnalysisTaskMsg 增加 contentHash 字段
// DebugController 发消息时：new AnalysisTaskMsg(id, "START_ANALYSIS", mediaService.contentHash(id))
// VideoAnalysisConsumer 消费时：msg.getContentHash()
```

#### 改造 G（P2）：结果复用（completedKey）

`analysis:completed:{contentHash}`，同一内容分析完成后，换 mediaId 直接复用结果，不再烧一遍 ASR + LLM。需引入「结果归属」机制，牵涉跨 mediaId 改写 `aiSummary`，本次仅列为后续进阶。

#### 改造 H（P2）：Redis 缓存 + 一次 IO 优化

- `media:md5:{mediaId}` 缓存（对标 `rememberContentHash`），避免每次查库。
- 直传链路用 `DigestInputStream` 一次 IO 完成「上传 + 算 MD5」。

### 4.5 上下文复用机制（转写结果复用）

> 理念：ASR 结果只取决于视频内容，与「归属用户 / 分析目标」无关。按 contentHash 复用转写文本，**同一内容只真正转写一次**，其余链路复用，彻底消除「转写与分析共用 ASR」的重复消耗。

#### 改造 I：归属键 + 统一转写入口

**① `AnalysisTaskKeys` 增加归属键**（对改造 C 扩展）：

```java
/** 内容级转写归属：记录哪个 mediaId 已产出该内容的转写文本。 */
public static String contextOwner(String contentHash) {
    return "analysis:context-owner:" + contentHash;
}
```

**② `AiService` 增加归属读写 + 复用查询**：

```java
private static final long CONTEXT_LOCK_WAIT_SECONDS = 300;  // 等待别人转写完成的窗口

/** 归属读取：返回已完成该内容转写的 mediaId，无则 null。 */
private Long transcriptOwner(String contentHash) {
    String value = redisTemplate.opsForValue().get(AnalysisTaskKeys.contextOwner(contentHash));
    return value == null ? null : Long.valueOf(value);
}

/** 归属登记：转写落库后写入，7 天 TTL。 */
private void rememberTranscriptOwner(String contentHash, Long mediaId) {
    redisTemplate.opsForValue().set(
            AnalysisTaskKeys.contextOwner(contentHash), String.valueOf(mediaId), Duration.ofDays(7));
}

/** 复用查询：本 mediaId 已有转写，或内容级归属可复用，返回文本；否则 null。 */
private String resolveTranscript(MediaFile mediaFile, String contentHash) {
    if (mediaFile.getTranscriptText() != null && !mediaFile.getTranscriptText().isBlank()) {
        return mediaFile.getTranscriptText();
    }
    Long ownerMediaId = transcriptOwner(contentHash);
    if (ownerMediaId != null && !ownerMediaId.equals(mediaFile.getId())) {
        MediaFile owner = mediaFileMapper.selectById(ownerMediaId);
        if (owner != null && owner.getTranscriptText() != null && !owner.getTranscriptText().isBlank()) {
            mediaFile.setTranscriptText(owner.getTranscriptText());
            mediaFile.setTranscriptStatus(AiStatus.SUCCESS.name());
            mediaFileMapper.updateById(mediaFile);
            return owner.getTranscriptText();
        }
        redisTemplate.delete(AnalysisTaskKeys.contextOwner(contentHash)); // 归属失效，清掉
    }
    return null;
}
```

**③ 统一转写入口（锁内「查归属 → 复用或转写 → 登记归属」）**：

```java
private String transcribeWithReuse(MediaFile mediaFile, String contentHash, boolean wait) {
    RLock lock = redissonClient.getLock(AnalysisTaskKeys.contextLock(contentHash));
    boolean locked = false;
    try {
        locked = wait
                ? lock.tryLock(CONTEXT_LOCK_WAIT_SECONDS, TimeUnit.SECONDS)  // 分析：等待复用
                : lock.tryLock();                                            // 独立转写：不等待
        String reusable = resolveTranscript(mediaFile, contentHash);          // 无论是否抢到锁都先查
        if (reusable != null) return reusable;
        if (!locked) return null;                                             // 没抢到且没复用：别人在转写，跳过
        String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
        mediaFile.setTranscriptText(text);
        mediaFile.setTranscriptStatus(AiStatus.SUCCESS.name());
        mediaFileMapper.updateById(mediaFile);
        rememberTranscriptOwner(contentHash, mediaFile.getId());              // 先落库再登记归属
        return text;
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("等待转写锁被中断", e);
    } finally {
        if (locked && lock.isHeldByCurrentThread()) lock.unlock();
    }
}
```

**④ 两条链路接入**：

- `asyncTranscribe`（`@Async` 独立转写）：`transcribeWithReuse(mediaFile, contentHash, false)`——不等待，拿不到锁跳过（别人在转写，结果最终落库，前端轮询可见）。
- `asyncAnalyze`（分析内转写）：`transcribeWithReuse(mediaFile, contentHash, true)`——等待复用（分析依赖转写结果做总结）。

**锁嵌套关系**：`asyncAnalyze` 外层已持 `analysisLock`，内层转写再拿 `contextLock`，顺序固定为 `analysisLock → contextLock`；`asyncTranscribe` 只拿 `contextLock`。无反向获取路径，不构成死锁。

**效果**：无论分析还是独立转写，谁先完成转写谁登记归属，另一条链路进来命中归属直接复用，ASR 只跑一次。

---

## 五、改造后的完整时序

以 AI 分析链路为例：

```
提交侧 DebugController.aiAnalyze
  ① 防连点锁   lock:analyze:{id}.tryLock(0,-1)     （保留，快速反馈）
  ② 查库拿 userId + fileMd5
  ③ 双层限流   requireAiQuota(userId)               （用户级 + 全局级）
  ④ 校验 aiStatus（PENDING/PROCESSING → CONFLICT）
  ⑤ 发 MQ（携带 contentHash = normalize(fileMd5)）
  ⑥ 释放锁
        │ 异步
        ▼
消费侧 VideoAnalysisConsumer.onMessage
  ⑦ 内容级锁   lock:analysis:{contentHash}.tryLock()  （身份化：跨 mediaId 串行）
  ⑧ asyncAnalyze（transcribe + summary）
  ⑨ 释放锁
```

限流在**提交侧**（准入控制），锁在**消费侧**（执行互斥），锁 key 以 **contentHash** 为身份。时序为 **限流 → MQ → 内容级加锁**。

---

## 六、文件变更清单

### 新增文件

```
service/RateLimitService.java                              # 双层限流统一入口
utils/AnalysisTaskKeys.java                                # 锁 key 生成 + MD5 标准化 + contextOwner 归属键
```

### 修改文件

```
entity/MediaFile.java                                      # （字段复用，无 DB 改动，仅注释说明 fileMd5=内容指纹）
service/MediaService.java                                  # +calculateMd5 +contentHash(mediaId)
controller/MediaController.java                            # 直传/URL 上传补算 MD5
controller/DebugController.java                            # 限流接入 + 顺序调整 + msg 携带 contentHash
dto/AnalysisTaskMsg.java                                   # +contentHash 字段（P1）
consumer/VideoAnalysisConsumer.java                        # 消费侧内容级锁
service/AiService.java                                     # asyncTranscribe/asyncAnalyze 内容级锁 + 上下文复用
service/ChunkUploadService.java                            # ✅ 已完成（看门狗）
```

---

## 七、实施顺序

| 步骤 | 改造 | 类型 | 优先级 |
|------|------|------|--------|
| 1 | 新建 `AnalysisTaskKeys`（key 生成 + 标准化） | 身份化 | P0 |
| 2 | `MediaService` 补 `calculateMd5` + `contentHash` | 身份化 | P0 |
| 3 | `MediaController` 直传/URL 补算 MD5 | 身份化 | P0 |
| 4 | 新建 `RateLimitService` | 限流 | P0 |
| 5 | `DebugController` 接入双层限流（ai + transcribe） | 限流 | P0 |
| 6 | `VideoAnalysisConsumer` 消费侧内容级锁 | 锁 | P0 |
| 7 | `AiService.asyncTranscribe` 内容级锁 | 锁 | P0 |
| 8 | 分片合并锁看门狗 | 锁 | ✅ 已完成 |
| 9 | `AnalysisTaskMsg` 携带 contentHash | 身份化 | P1 |
| 10 | 上下文复用机制（转写结果复用） | 复用 | P1 |
| 11 | 结果复用 completedKey / Redis 缓存 / 一次 IO | 复用 | P2 |

> 依赖关系：改造 6 / 7 依赖改造 1~3（锁 key 需要 contentHash）；改造 9 依赖改造 2（消息携带指纹）。

---

## 八、验证方式

1. **消费侧内容级锁**：同一内容（相同 MD5）以不同 mediaId 并发提交分析，确认只有一次 `asyncAnalyze` 执行，其余跳过。
2. **转写内容级锁**：同一内容并发调用 `/debug/transcribe`（不同 mediaId），确认只跑一次 ASR。
3. **直传 MD5 落库**：小文件直传与 URL 上传后，`fileMd5` 非空且为 32 位 hex。
4. **标准化回退**：历史数据（`fileMd5` 为空）分析时，锁 key 回退为 `media-{id}`，功能不降级。
5. **双层限流**：单用户连续调用超 5 次/分返回 429；多用户合计超 30 次/分返回 429。
6. **提取限流**：`/debug/transcribe` 超 10 次/分（用户）或 60 次/分（全局）返回 429。
7. **失败语义**：临时停 Redis，确认返回 503（`SERVICE_UNAVAILABLE`）而非 429。
8. **合并锁回归**：构造超长合并，确认锁在合并期间不过期。
9. **转写复用**：同一内容先 `/debug/transcribe` 再 `/debug/ai`（或反之），确认 ASR 只调用一次，后者命中归属直接复用。

---

## 九、风险与注意事项

| 风险 | 说明 | 对策 |
|------|------|------|
| 历史数据无指纹 | 直传改造前上传的视频 `fileMd5` 为空 | `normalizeContentHash` 非法回退 `media-{id}`，功能不降级 |
| 内容级锁的粒度变化 | 锁从「按记录」变「按内容」，同一内容跨 mediaId 会互斥 | 正是改造目标；语义从「防同一记录重复」升级为「防同一内容重复」 |
| 转写与分析共用 ASR | 分析内部也调 transcribe，可能与独立 `/debug/transcribe` 争抢同一内容 | 由 4.5 上下文复用机制解决：统一转写入口 `transcribeWithReuse` + 归属键，两条链路争抢同一把 `contextLock`，先转写者登记归属，后者复用 |
| @Async 线程占用 | 线程池里 `tryLock` 若等待会占线程 | 强制 `tryLock()` 不等待，抢不到直接 return |
| 提交侧锁与消费侧锁并存 | 提交侧 `lock:analyze:{id}` 与消费侧 `lock:analysis:{contentHash}` 不冲突 | key 不同且时间不重叠；提交侧锁退化防连点 |
| 限流顺序变化 | `aiAnalyze` 从「限流在查库前」改为「查库后」 | 多一次查库开销可忽略，换取用户级限流所需 userId |
| 匿名用户 | `userId` 可能为 null | 限流 key 统一用 `anon`；contentHash 与 userId 无关不受影响 |
| 分片合并锁语义 | 合并锁按 uploadId 而非 contentHash | 正确：合并保护「一次上传会话」而非「内容」，无需身份化 |
