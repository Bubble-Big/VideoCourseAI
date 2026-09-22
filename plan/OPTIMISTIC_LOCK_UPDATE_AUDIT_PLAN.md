# 乐观锁 updateById 返回值巡查与修复计划书

> 状态：**✅ 已完成**（创建于 2026-09-21，完成于 2026-09-22）。
>
> 背景：`MybatisPlusConfig.java` 注册了 `OptimisticLockerInnerInterceptor` 后，所有带 `@Version` 字段实体（`MediaAiAnalysis`、`MediaTranscription`）的 `updateById()` 调用，会自动拼接 `WHERE version=?` 条件——命中则更新并回填新 version，**未命中则静默返回 0，不抛异常**。插件注册前，`updateById` 总是按主键精确命中，忽略返回值是安全的；插件注册后，任何未检查返回值的调用点都变成了潜在的"丢失更新"风险：数据库实际未落库，但代码继续走"成功"分支（登记 Redis 归属、推送 SSE、返回结果给调用方）。
>
> 本轮巡查由多条代码 review 报告触发（指出 `ContentTaskGate.java`、`AiService.java` 多处 `updateById` 未检查返回值），在此基础上对整个 `server/src/main/java` 做了全量 `updateById` 调用点排查，确认共 14 处调用，其中 5 处已安全，9 处存在不同程度的风险。
>
> **实施结果**：
> - Phase 1 (P0 高危) 4 处修复：ContentTaskGate 归属查询/复用/转写查询 + AiService 转写成功落库
> - Phase 2 (P1 中危) 3 处修复：AiService 瞬时失败刷新 + asyncTranscribe 失败兜底 + markFailed 同步转写状态
> - Phase 3 (P2 低危) 2 处修复：asyncTranscribe 超时回滚 + transcribeWithReuse NONE→PROCESSING
> - **共修复 9 处乐观锁冲突，全部通过编译验证**

---

## 一、根因机制：version 是如何在锁外被推高的

`ContentTaskGate` 的 `inAnalysisLock`/`inTranscribeLock`（Redisson 分布式锁，按 `contentHash` 加锁）只保证**同一内容**的分析/转写调用彼此串行，但**不阻止锁外其他路径直接修改同一 `mediaId` 的 DB 记录**。

`AbstractCompensationScheduler.compensateOne()` 与 `incrementAttemptsIfStillPending()` 使用原生 SQL（`LambdaUpdateWrapper.apply("media_id = {0} AND version = {1}", ...)`）在锁外更新 `process_at`、`compensation_attempts`、`status` 字段，每次更新都会推高目标记录的 `version`。

因此，当持锁中的 `asyncAnalyze`/`asyncTranscribe`/`transcribeWithReuse` 手里拿着一份旧 version 的内存快照，在它执行 `updateById` 之前，补偿调度器已经在锁外把同一条记录的 version 往前推了一格——`updateById` 会静默失败（返回 0），但如果调用点没有检查返回值，代码会误以为落库成功，继续执行后续的"宣布成功"动作。

这就是本次巡查要清理的问题模式。已修复的 `AiService.markFailed()`（第 364/380 行）是本计划统一采用的修复范式：**检查返回值 → 为 0 时重新查询最新记录 → 判断是否已是终态（是则跳过）→ 否则基于最新 version 重试一次 → 仍失败则放弃并记录警告日志**。

---

## 二、全量巡查结果清单

对 `server/src/main/java` 全目录执行 `updateById(` 检索，共 14 处调用点：

| 文件 | 行号 | 所在方法 | 现状 | 风险等级 |
|------|------|----------|------|----------|
| AiService.java | 115 | asyncAnalyze（置 PROCESSING） | ✅ 已检查 | 安全 |
| AiService.java | 136 | asyncAnalyze（无语音落 SUCCESS） | ✅ 已检查 | 安全 |
| AiService.java | 160 | asyncAnalyze（正常落 SUCCESS） | ✅ 已检查 | 安全 |
| AiService.java | 203 | handleAnalysisException（瞬时失败保持 PROCESSING） | ✅ 已修复 | 安全 |
| AiService.java | 253 | asyncTranscribe（等待锁超时回滚 NONE） | ✅ 已修复 | 安全 |
| AiService.java | 272 | asyncTranscribe（异常兜底落 FAILED） | ✅ 已修复 | 安全 |
| AiService.java | 315 | transcribeWithReuse（NONE→PROCESSING 刷新） | ✅ 已修复 | 安全 |
| AiService.java | 335 | transcribeWithReuse（真正转写成功落 SUCCESS） | ✅ 已修复 | 安全 |
| AiService.java | 364 | markFailed（aiAnalysis 落 FAILED） | ✅ 已修复 | 安全 |
| AiService.java | 380 | markFailed（重试一次落 FAILED） | ✅ 已修复 | 安全 |
| AiService.java | 393 | markFailed（同步转写记录落 FAILED） | ✅ 已修复 | 安全 |
| ContentTaskGate.java | 237 | resolveAnalysis（分析结果复用回填） | ✅ 已修复 | 安全 |
| ContentTaskGate.java | 263 | resolveAnalysis（一并回填转写文本） | ✅ 已修复 | 安全 |
| ContentTaskGate.java | 375 | resolveTranscript（转写结果复用回填） | ✅ 已修复 | 安全 |

**全部 14 处调用点已安全**：5 处原本已检查 + 9 处本轮修复完成。

---

## 三、高危问题详情（会向前端播报虚假成功，或污染跨内容复用链）

### 3.1 ContentTaskGate.java:237 — resolveAnalysis() 分析结果回填

```java
currentAnalysis.setSummary(owner.getSummary());
currentAnalysis.setStatus(AiStatus.SUCCESS.name());
currentAnalysis.setProcessAt(LocalDateTime.now());
aiAnalysisMapper.updateById(currentAnalysis);   // 第237行：未检查
```

**触发场景**：`currentAnalysis` 记录的 version 与内存快照不一致时（例如补偿调度器刚在锁外刷新过该记录的 `process_at`），`updateById` 静默返回 0，`summary`/`status` 实际未落库为 SUCCESS。但代码后续无条件执行 `rememberAnalysis`，把该 `mediaId` 登记为 Redis 归属所有者并推送 SSE SUCCESS。之后其他内容相同的记录复用此 `mediaId` 作为 owner 时，会拿到一条 DB 中并非真正 SUCCESS 的空/旧结果。

### 3.2 ContentTaskGate.java:263 — resolveAnalysis() 转写文本一并回填

```java
currentTranscription.setTranscriptText(owner.getTranscriptText());
currentTranscription.setStatus(AiStatus.SUCCESS.name());
currentTranscription.setProcessAt(LocalDateTime.now());
transcriptionMapper.updateById(currentTranscription);   // 第263行：未检查
// ... 267-268行：无条件推送 SSE transcription SUCCESS
```

**触发场景**：与 3.1 同一并发窗口下，转写文本未落库，但仍推送 SSE transcription SUCCESS 事件并携带 owner 的文本。前端收到成功事件，刷新页面后发现转写记录实际还是旧状态，出现状态回退不一致。

### 3.3 ContentTaskGate.java:375 — resolveTranscript() 转写结果回填

```java
currentTranscription.setTranscriptText(owner.getTranscriptText());
currentTranscription.setStatus(AiStatus.SUCCESS.name());
currentTranscription.setProcessAt(LocalDateTime.now());
transcriptionMapper.updateById(currentTranscription);   // 第375行：未检查
// ... 378行：登记归属；381-382行：推送 SSE SUCCESS；384行：return owner.getTranscriptText();
```

**触发场景**：与前两处相同的机制，此处额外多了一个后果——方法直接把 `owner.getTranscriptText()` 当作已持久化的结果 `return` 给调用方（`transcribeWithReuse`），调用方会把这段文本当作真实转写结果继续往下传递（例如喂给 LLM 做总结），但 DB 里这条转写记录并未真正落库为 SUCCESS。

### 3.4 AiService.java:335 — transcribeWithReuse() 真正转写成功落库

```java
transcription.setTranscriptText(text);
transcription.setStatus(AiStatus.SUCCESS.name());
transcription.setProcessAt(LocalDateTime.now());
transcriptionMapper.updateById(transcription);   // 第335行：未检查
// ... 338-340行：登记归属；343行：推送 SSE SUCCESS
// ... resultHolder[0] = text; 返回给 asyncAnalyze/asyncTranscribe 调用方
```

**触发场景**：这是"真正跑了一次 ASR"之后的落库，如果此处静默失败，代价最大——真实转写结果（可能耗时数十秒）未落库，却已经登记为跨内容复用的归属来源，后续其他相同内容的记录会复用到一个 DB 中不存在的结果；同时当前调用方（`asyncAnalyze`）会拿着内存里的 `text` 继续做 LLM 总结并落 `aiAnalysis.SUCCESS`，形成"分析成功但转写记录本身未落库"的数据不一致。

---

## 四、中危问题详情（状态不一致，但不直接播报虚假成功）

### 4.1 AiService.java:203 — handleAnalysisException() 瞬时失败分支

```java
aiAnalysis.setStatus(AiStatus.PROCESSING.name());
aiAnalysis.setProcessAt(LocalDateTime.now());
aiAnalysisMapper.updateById(aiAnalysis);   // 第203行：未检查
```

**触发场景**：`markFailedFinal`（DLQ 无锁路径）与持锁中的瞬时失败重试同时作用于同一 `mediaId` 时，此处刷新 `processAt` 的写入可能被版本冲突静默吞掉。但代码仍推送 SSE PROCESSING，掩盖了时间戳实际未刷新的事实，可能影响补偿调度器基于 `processAt` 阈值的卡死判定（记录会被过早地再次判定为"卡死"）。

### 4.2 AiService.java:272 — asyncTranscribe() 异常兜底落 FAILED

```java
transcription.setStatus(AiStatus.FAILED.name());
transcription.setTranscriptText(null);
transcription.setProcessAt(LocalDateTime.now());
transcriptionMapper.updateById(transcription);   // 第272行：未检查
// ... 277行：推送 SSE transcription FAILED
```

**触发场景**：`updateById` 静默失败时，DB 中记录可能仍是 PROCESSING（或已被并发路径写为其他终态），但代码仍推送 SSE FAILED，前端展示的状态与 DB 实际状态不符。刷新页面后会看到与刚才 SSE 推送不一致的状态。

### 4.3 AiService.java:393 — markFailed() 同步转写状态为 FAILED

```java
if (transcription != null && !AiStatus.SUCCESS.name().equals(transcription.getStatus())) {
    transcription.setStatus(AiStatus.FAILED.name());
    transcriptionMapper.updateById(transcription);   // 第393行：未检查
}
```

**触发场景**：`markFailed` 对 `aiAnalysis` 记录本身（364/380 行）已有完整的冲突检测 + 重试兜底，但对 `transcription` 记录的这处同步更新没有同样保护。若转写记录同时被其它路径更新（如补偿调度器刷新 `process_at`），此处静默失败会导致 `transcription.status` 与 `aiAnalysis.status`（FAILED）不同步，违反类注释所述"避免与 aiStatus 不一致"的设计意图。

---

## 五、低危问题详情（面向重试的宽松容错分支，可选修复）

### 5.1 AiService.java:253 — asyncTranscribe() 等待锁超时回滚 NONE

```java
transcription.setStatus(AiStatus.NONE.name());
transcription.setProcessAt(null);
transcriptionMapper.updateById(transcription);   // 第253行：未检查
```

**风险评估**：这是"放弃本次、允许后续重试"的兜底分支。即使此处静默失败，记录大概率仍停留在某个非终态（PROCESSING 或原状态），后续的用户手动重试或补偿调度器仍能重新拿到它。不会误报成功，最坏情况只是本次回滚未生效、记录多等一轮补偿周期。

### 5.2 AiService.java:315 — transcribeWithReuse() NONE→PROCESSING 刷新

```java
transcription.setStatus(AiStatus.PROCESSING.name());
transcription.setProcessAt(LocalDateTime.now());
transcriptionMapper.updateById(transcription);   // 第315行：未检查
```

**风险评估**：只是进入处理态前的状态刷新，即使静默失败，后续第 328-335 行仍会继续执行真正的转写逻辑并在完成后落 SUCCESS（该处已列入 3.4 高危修复范围）。此处失败的直接后果只是 `processAt` 未及时刷新，与 4.1 类似，可能轻微影响补偿调度器的卡死判定时机。

---

## 六、统一修复方案模式

参照已修复的 `AiService.markFailed()`（364-385 行），按调用点的语义分两类处理：

**模式 A：终态写入（SUCCESS/FAILED），且后续有"播报成功/返回结果给调用方"动作**（对应 3.1-3.4、4.2、4.3）

```java
int updated = xxxMapper.updateById(entity);
if (updated == 0) {
    // 重新查询最新记录，判断是否已是终态
    Xxx latest = xxxMapper.selectOne(
        new LambdaQueryWrapper<Xxx>().eq(Xxx::getMediaId, mediaId)
    );
    if (latest == null || 是目标终态或其他终态) {
        // 已被并发路径写为终态，跳过本次兜底，不再重复播报
        log.info("...被跳过（记录已丢失或已被并发写为最终态），mediaId={}", mediaId);
        return ...;
    }
    // 基于最新 version 重试一次
    latest.set...(...);
    int retried = xxxMapper.updateById(latest);
    if (retried == 0) {
        log.warn("...重试仍冲突，放弃本次操作, mediaId={}", mediaId);
        return ...;
    }
    entity = latest; // 后续动作（SSE推送/登记归属/return）基于 latest 执行
}
// 原有的登记归属 / SSE 推送 / return 逻辑
```

**模式 B：过程态刷新（PROCESSING 保持/回滚 NONE），无对外播报或播报内容不含结果数据**（对应 4.1、5.1、5.2）

```java
int updated = xxxMapper.updateById(entity);
if (updated == 0) {
    log.info("...刷新被跳过（版本冲突，记录已被并发路径更新），mediaId={}", mediaId);
}
// 不影响后续 SSE 推送（推送的是状态本身，不是数据），可以继续执行
```

模式 B 不需要重试兜底，因为：① 冲突通常意味着记录已被别的路径（补偿调度器/另一个终态写入）更新，此时"再抢一次"没有必要；② 这类分支本身就是"进入处理态"或"放弃本次"的过渡态，下一轮补偿/重试会自然覆盖。

---

## 七、实施优先级建议

| 优先级 | 调用点 | 理由 |
|--------|--------|------|
| P0（高危，优先修复） | ContentTaskGate.java:237、263、375；AiService.java:335 | 会导致向前端播报虚假成功，或把未持久化的结果当作真实结果传递给下游（LLM 总结/跨内容复用链），影响面最大 |
| P1（中危，次优先） | AiService.java:203、272、393 | 状态不一致，可能干扰补偿调度器判定或造成 SSE 与 DB 状态短暂不符，但不会直接污染复用链 |
| P2（低危，可选） | AiService.java:253、315 | 面向重试的过渡态刷新，静默失败的后果会被后续重试/补偿周期自然吸收 |

建议按 P0 → P1 → P2 顺序逐条修复，每完成一批调用 `/compile-server` skill 验证编译通过。

---

## 八、修复范围说明

本计划仅覆盖 `AiService.java` 与 `ContentTaskGate.java` 中带 `@Version` 字段实体（`MediaAiAnalysis`、`MediaTranscription`）的 `updateById` 调用点。`AbstractCompensationScheduler` 中的原生 SQL 更新（`compensateOne`/`incrementAttemptsIfStillPending`）已自带 `version` 条件与返回值检查，不在本次修复范围内。`MediaFile` 实体当前无 `@Version` 字段，其相关 `updateById` 调用不受本次插件注册影响，同样不在本次修复范围内。
