# 分布式锁 + 令牌桶限流 + MD5 内容身份化改造计划书

> 状态：**已实施**（最后更新 2026-08-14）

## 一、背景与目标

VideoCourseAI 原先在分布式锁、限流、MD5 身份化上只有「基础骨架」：锁在提交侧毫秒级、消费侧无锁、限流单层全局、直传不算 MD5。本次改造对齐工程化实现，核心理念：

> **视频的「身份」= 内容指纹（MD5，即 contentHash），而非数据库自增 mediaId。** 锁、幂等、复用均以 contentHash 为身份，实现跨 mediaId / 跨用户的串行化与复用。

三条改造线：

1. **锁**：放到副作用发生处（消费/执行侧），持锁覆盖完整副作用；长持锁用看门狗，不用固定租约。
2. **限流**：升级为双层（用户级 + 全局级），覆盖分析 / 提取两个入口，区分「真超限」与「Redis 异常」。
3. **MD5 身份化**：上传即算指纹，锁 / 幂等 / 复用全链路以 contentHash 为身份。

复用现有 `fileMd5` 字段作为内容指纹，**不新增 DB 字段**（避免改 schema）。

## 二、Redis Key 全景（改造后的核心产物）

| Key | 类型 | 身份 | 生命周期 | 用途 |
|-----|------|------|---------|------|
| `analysis:active:{contentHash}` | String | 内容 | SET NX EX 30s | 提交侧幂等，抢不到直接拒 |
| `lock:analysis:{contentHash}` | RLock | 内容 | 看门狗，持锁到分析完成 | 消费侧串行，防同内容并发 / 重投 |
| `lock:analysis-context:{contentHash}` | RLock | 内容 | 看门狗，转写窗口 | 同一内容只转写一次 |
| `analysis:context-owner:{contentHash}` | String | 内容 | 7d | 转写结果归属，跨 mediaId 复用 |
| `analysis:completed-owner:{contentHash}` | String | 内容 | 7d | 分析结果归属，跨 mediaId 复用 summary |
| `media:md5:{mediaId}` | String | 记录 | 7d | contentHash 缓存，免查库 |
| `limit:ai:user:{userId}` / `limit:ai:global` | RRateLimiter | 用户/全局 | 令牌桶 5/30 每分 | 分析双层限流 |
| `limit:transcribe:user:{userId}` / `limit:transcribe:global` | RRateLimiter | 用户/全局 | 令牌桶 10/60 每分 | 提取双层限流 |
| `lock:merge:{uploadId}` 等 | RLock/Hash/Set | 会话 | 看门狗/48h | 分片上传合并（保持 uploadId，不身份化） |

## 三、改造方案

### 3.1 MD5 内容身份化

- **直传补算**：小文件直传 `MediaController.upload` 与 URL 上传 `upload-url` 上传前算 MD5 写 `fileMd5`（分片合并已有）。
- **一次 IO**：直传用 `DigestInputStream` 边上传边算 MD5（`MinioUtils.uploadFile(file, digest)`）。
- **统一获取**：`MediaService.contentHash(mediaId)` = Redis 缓存 `media:md5:{mediaId}` → DB `fileMd5` → 标准化。
- **标准化回退**：`normalizeContentHash(mediaId, md5)` 合法 MD5 小写返回；非法（历史数据 / 直传前上传）回退 `media-{id}`，功能不降级。

核心工具类（key 定义集中于此）：

```java
public final class AnalysisTaskKeys {
    public static String normalizeContentHash(Long mediaId, String md5) { /* 合法 MD5 小写返回，否则 media-{id} */ }
    public static String active(String contentHash)         { return "analysis:active:" + contentHash; }
    public static String analysisLock(String contentHash)   { return "lock:analysis:" + contentHash; }
    public static String contextLock(String contentHash)    { return "lock:analysis-context:" + contentHash; }
    public static String contextOwner(String contentHash)   { return "analysis:context-owner:" + contentHash; }
    public static String completedOwner(String contentHash) { return "analysis:completed-owner:" + contentHash; }
}
```

### 3.2 分布式锁

**提交侧：setIfAbsent 幂等键（替代原 RLock）**

`DebugController.aiAnalyze` 先查库拿 contentHash，`setIfAbsent(analysis:active:{contentHash}, mediaId, 30s)`，抢不到直接 `CONFLICT`。失败（限流超限 / 状态冲突 / 发 MQ 异常）`delete` 回滚允许重试；提交成功靠 TTL 自然过期，后续由 `aiStatus` 状态校验接管。身份从 mediaId 升级为 contentHash，提交侧也能挡「换 mediaId 重复上传」。

**消费侧：内容级锁（看门狗）**

`VideoAnalysisConsumer.onMessage` 以 `lock:analysis:{contentHash}` `tryLock()`，抢不到静默 ACK（同内容并发 / 重投 / 换 mediaId 重复上传），持锁到 `asyncAnalyze` 完成。

**转写：内容级锁 + 归属复用**

`AiService.transcribeWithReuse(mediaFile, contentHash, wait)`：抢 `contextLock` → 查 `contextOwner` 归属，命中则复用转写文本；未命中且抢到锁才真正 ASR，落库后登记归属。分析链路 `wait=true`（等 300s 复用），独立转写 `wait=false`（抢不到跳过，不占线程池）。

**锁嵌套顺序**：`analysisLock → contextLock`，`asyncTranscribe` 仅拿 `contextLock`，无反向路径，不构成死锁。

**分片合并锁**：已是看门狗 `tryLock()`，按 `uploadId`（会话）而非 contentHash——合并保护「一次上传会话」，不身份化。

### 3.3 令牌桶限流

新建 `RateLimitService` 统一入口：

- `requireAiQuota(userId)`：`limit:ai:user:{userId}`（5/分）+ `limit:ai:global`（30/分）。
- `requireTranscribeQuota(userId)`：`limit:transcribe:user:{userId}`（10/分）+ `limit:transcribe:global`（60/分）。
- 真超限抛 `RATE_LIMITED`(429)；Redis 异常抛 `SERVICE_UNAVAILABLE`(503)，由 `ApiExceptionHandler` 按 `ErrorCode.httpStatus` 映射。
- `DebugController.aiAnalyze` 顺序改为「查库 → 限流」（用户级限流需要 userId）；`transcribe` 同样接入。

### 3.4 结果复用

`asyncAnalyze` 在转写前先查 `analysis:completed-owner:{contentHash}`：命中则复制 summary + 转写文本直接返回（省 ASR + LLM）；成功后登记归属。配合转写复用，同一内容跨 mediaId 只真正分析一次。

## 四、改造后时序（AI 分析链路）

```
提交侧 DebugController.aiAnalyze
  ① setIfAbsent(analysis:active:{contentHash}, 30s)   —— 内容级幂等，抢不到 409
  ② 双层限流 requireAiQuota(userId)                    —— 真超限 429 / Redis 异常 503
  ③ 校验 aiStatus（PENDING/PROCESSING → 409）
  ④ set PENDING → 发 MQ（携带 contentHash）
        │ 异步
        ▼
消费侧 VideoAnalysisConsumer.onMessage
  ⑤ tryLock(lock:analysis:{contentHash})               —— 内容级串行，抢不到 ACK 跳过
  ⑥ asyncAnalyze：结果复用 → 转写(锁 + 归属复用) → summary
  ⑦ 释放锁
```

限流在提交侧（准入），锁在消费侧（执行互斥），身份统一为 contentHash。

## 五、文件变更清单

**新增**：`utils/AnalysisTaskKeys.java`（key + 标准化）、`service/RateLimitService.java`（双层限流）。

**修改**：

- `entity/MediaFile.java`：注释说明 `fileMd5` = contentHash（无 schema 改动）。
- `service/MediaService.java`：+`calculateMd5` +`contentHash`（带缓存）+`md5Digest`。
- `utils/MinioUtils.java`：+`uploadFile(file, digest)` 一次 IO。
- `controller/MediaController.java`：直传 / URL 补算 MD5。
- `dto/AnalysisTaskMsg.java`：+`contentHash`。
- `controller/DebugController.java`：双层限流 + 幂等键 + msg 带 contentHash。
- `consumer/VideoAnalysisConsumer.java`：消费侧内容级锁。
- `service/AiService.java`：内容级锁 + 归属复用 + 结果复用。
- `strategy/AiAnalysisStrategy.java` + `AliyunDeepSeekStrategy.java`：+`generateSummaryFromText`。
- `service/ChunkUploadService.java`：✅ 已完成（看门狗）。

## 六、验证方式

1. 消费侧内容级锁：同内容不同 mediaId 并发分析，只跑一次 `asyncAnalyze`。
2. 转写复用：同内容先 `/debug/transcribe` 再 `/debug/ai`，ASR 只调一次。
3. 直传 MD5 落库：`fileMd5` 非空且 32 位 hex。
4. 标准化回退：历史数据（`fileMd5` 空）回退 `media-{id}`，功能不降级。
5. 双层限流：单用户超 5 次/分 429；全局超 30 次/分 429；提取 10/60 每分。
6. 失败语义：停 Redis 返回 503（`SERVICE_UNAVAILABLE`）。
7. 幂等键：连点 30s 内被拒；发 MQ 失败后可立即重试。

## 七、风险与注意事项

| 风险 | 对策 |
|------|------|
| 历史数据无指纹 | `normalizeContentHash` 非法回退 `media-{id}` |
| 内容级锁粒度变化（跨 mediaId 互斥） | 正是改造目标：从「防记录重复」升级为「防内容重复」 |
| 转写与分析共用 ASR | `transcribeWithReuse` + 归属键，两条链路争同一把 `contextLock`，先完成者登记归属 |
| @Async 线程占用 | `tryLock()` 不等待，抢不到立即 return |
| 幂等键 TTL 过期误删 | 失败才回滚、成功靠 TTL 过期，避免误删他人重设的键 |
| 结果复用无法强制重分析 | 当前无「强制重分析」入口，影响有限；后续需在提交侧加 force 标记绕过 |
| 匿名用户 | 限流 key 统一 `anon`；contentHash 与 userId 无关 |
| 分片合并锁 | 按 `uploadId` 不身份化（合并保护会话而非内容） |

## 八、实施记录（相对原计划的关键偏离）

| # | 偏离 | 说明 |
|---|------|------|
| 1 | 改造 7 升级为上下文复用 | 单独做转写锁（抢不到 return）会让「同内容不同 mediaId」后来者卡 PROCESSING，故落地为 `transcribeWithReuse` 归属复用 |
| 2 | 新增 `generateSummaryFromText` | 原 `generateSummary` 内部重复 ASR，新增基于已转写文本的总结，真正「同一内容只转写一次」 |
| 3 | 结果复用键命名 | 采用 `analysis:completed-owner:{contentHash}`（对称 `contextOwner`），非原文 `analysis:completed` |
| 4 | 提交侧锁改幂等键 | `lock:analyze:{mediaId}` → `analysis:active:{contentHash}`（内容级、秒级 TTL、失败回滚），消费侧保留 Redisson 看门狗锁 |
