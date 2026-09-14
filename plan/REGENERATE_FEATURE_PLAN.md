# 重新生成功能实现计划

> 创建日期：2026-09-15  
> 状态：待实现  
> 预计工作量：6 小时

---

## 一、功能概述

### 问题
- AI 分析失败或结果不满意时，无法直接重新生成，只能删除重新上传
- 文字提取失败后无法直接重试

### 目标
- AI 分析：侧边栏常驻"重新生成"按钮（SUCCESS/FAILED 状态显示）
- 文字提取：仅失败时显示"重新生成"按钮（语音转文字通常很稳定）

### 设计原则
- 简单：最小化改动，复用现有架构
- 安全：限流 + 确认提示
- 独立：不影响其他用户的复用结果

---

## 二、技术方案

### 核心思路

添加 `force` 参数控制是否跳过复用：
- `force=false`（默认）：尝试复用其他用户结果
- `force=true`（重新生成）：跳过复用，直接调用 AI

**重新生成流程与首次流程一致**：
- 走相同的 MQ 消息队列
- 走相同的异步分析流程
- 走相同的 SSE 状态推送
- 唯一区别：force=true 跳过复用逻辑

**关键特性**：
1. 只更新当前 mediaId 的记录
2. 不更新 Redis 归属缓存（避免污染复用链）
3. 不影响其他用户

### force 参数行为对比

| 操作 | force=false | force=true |
|------|------------|-----------|
| 复用逻辑 | 尝试复用 | 跳过复用 |
| 归属缓存 | 登记归属 | 不登记（保持原归属稳定） |
| 限流 | 正常限流 | 正常限流 |
| 幂等保护 | PENDING/PROCESSING 不重复提交 | 重置为 NONE 后重新提交 |

**为什么不更新归属缓存？** 避免个人重新生成的劣质结果覆盖已被广泛复用的优质结果。

---

## 三、实现步骤

### 阶段 1：后端改动（2 小时）

#### 文件改动清单

1. **AnalysisTaskMsg.java** - 添加 `Boolean force` 字段
2. **DebugController.java** - `analyzeVideo` 和 `transcribeVideo` 添加 force 参数
   - force=true 时重置状态为 NONE，清空旧结果
   - MQ 消息携带 force 参数
3. **VideoAnalysisConsumer.java** - 传递 force 参数给 AiService
4. **AiService.java** - `asyncAnalyze`、`asyncTranscribe`、`transcribeWithReuse` 支持 force
   - force=false 时尝试复用
   - force=true 时跳过 `resolveAnalysis` 和 `resolveTranscript`
   - 只在 force=false 时调用 `rememberAnalysis` 和 `rememberTranscript`

#### 核心逻辑示意

```java
// DebugController - force=true 重置状态
if (force) {
    mediaFile.setAiStatus(AiStatus.NONE.name());
    mediaFile.setAiSummary(null);
    mediaFileMapper.update(...);
}

// AiService - force 控制复用
if (!Boolean.TRUE.equals(force)) {
    if (contentTaskGate.resolveAnalysis(mediaFile, contentHash)) {
        return GateOutcome.REUSE;  // 复用成功
    }
}
// 执行真正的分析...

// 只在非 force 时登记归属
if (!Boolean.TRUE.equals(force)) {
    contentTaskGate.rememberAnalysis(contentHash, mediaId);
}
```

---

### 阶段 2：前端改动（2 小时）

#### 文件改动清单

1. **api/index.js** - `analyzeVideo` 和 `transcribeVideo` 添加 force 参数（默认 false）
2. **ResultSidebar.vue** - 添加"重新生成"按钮 + 交互逻辑
   - **AI 分析**：SUCCESS/FAILED 状态都显示按钮（用户可能对成功结果不满意）
   - **文字提取**：仅 FAILED 状态显示按钮（语音转文字通常很稳定，无需重新生成）
   - 点击前弹窗确认："重新生成将消耗 AI 配额，确定继续吗？"
   - 用户确认后调用 API，传递 force=true
   - 添加 regenerating 状态和 loading 样式
3. **useMedia.js** - 传递完整状态（mediaId、state、content、error）到侧边栏

#### UI 交互流程

```
AI 分析侧边栏：
┌─────────────────────────────┐
│ [🔄 重新生成] [📥 下载报告]  │ ← SUCCESS 时显示（允许重新生成）
└─────────────────────────────┘

┌─────────────────────────────┐
│ [🔄 重新生成]                │ ← FAILED 时显示
└─────────────────────────────┘

文字提取侧边栏：
┌─────────────────────────────┐
│ （无重新生成按钮）           │ ← SUCCESS 时不显示（结果稳定）
└─────────────────────────────┘

┌─────────────────────────────┐
│ [🔄 重新生成]                │ ← FAILED 时显示
└─────────────────────────────┘

点击"重新生成"流程（与首次点击一致）：
1. confirm 确认弹窗："重新生成将消耗 AI 配额，确定继续吗？"
2. 用户确认后，调用 api.analyzeVideo(id, true) 或 api.transcribeVideo(id, true)
3. 后端：重置状态为 NONE → 发送 MQ 消息（force=true）→ 走完整异步流程
4. 前端：按钮显示 loading 状态 → SSE 自动推送状态更新 → 侧边栏实时显示新结果
```

---

### 阶段 3：测试验证（2 小时）

#### 核心测试用例

1. **AI 分析成功后重新生成**：SUCCESS 状态显示按钮，点击后弹窗确认，SSE 实时推送新结果
2. **AI 分析失败后重试**：FAILED 状态显示按钮，点击重试成功
3. **文字提取重新生成**：同 AI 分析流程
4. **force 不影响其他用户**：用户 B force=true 后，用户 C 仍复用用户 A 的结果
5. **限流保护**：连续点击 6 次触发 429 错误
6. **并发安全**：多用户同时 force=true，分布式锁保护，各自更新各自记录
7. **PROCESSING 状态重新生成**：force=true 时重置为 NONE 后重新提交

---

## 四、用户体验流程

1. **AI 分析成功后重新生成**：侧边栏显示结果 + 重新生成按钮 → 点击弹窗确认 → SSE 实时推送新结果
2. **AI 分析失败后重试**：侧边栏显示失败提示 + 重新生成按钮 → 点击重试
3. **文字提取重新生成**：同 AI 分析流程

---

## 五、风险与成本

### 风险缓解

- 用户滥用：双层限流（用户 5/分 + 全局 30/分）
- 破坏复用链：force=true 不更新归属缓存
- 并发冲突：分布式锁保护
- 误操作：确认弹窗提示

### 成本评估

假设 1000 用户/天，5% 使用重新生成（50 次），每次 ¥0.1  
新增成本：¥5/天 = ¥150/月（可控）

---

## 六、验收标准

**功能**：SUCCESS/FAILED 状态显示按钮，force=true 不影响其他用户，限流生效，并发安全  
**性能**：接口响应 < 200ms，SSE 推送 < 100ms  
**安全**：限流保护，确认弹窗，归属缓存不被破坏

---

## 七、部署与回滚

**部署**：后端编译重启 → 前端编译重启 → 执行核心测试  
**回滚**：前端隐藏按钮 / 后端忽略 force 参数（无数据库变更）

---

## 八、后续优化

- 显示"本次结果是否复用"提示
- 重新生成按钮添加冷却时间（30 秒）
- 埋点统计重新生成次数、成功率、成本
- 支持查看历史生成结果（需新表）

---

## 九、文件清单

**后端（5 个）**：
- `dto/AnalysisTaskMsg.java` - 添加 force 字段
- `controller/DebugController.java` - analyzeVideo/transcribeVideo 添加 force 参数
- `consumer/VideoAnalysisConsumer.java` - 传递 force 参数
- `service/AiService.java` - asyncAnalyze/asyncTranscribe/transcribeWithReuse 支持 force

**前端（3 个）**：
- `api/index.js` - 添加 force 参数
- `components/ResultSidebar.vue` - 添加重新生成按钮 + 交互
- `composables/useMedia.js` - 传递完整状态

**数据库/配置**：无需变更 ✅

---

## 十、已知问题与漏洞修复计划

> 基于代码全面审查（2026-09-15），识别出的安全与一致性问题

### 🔴 P0 严重问题（需立即修复）

---

#### 问题 2：用户手动重试未使用乐观锁

**位置**：`DebugController.java:106-112`

**问题描述**：
状态重置时没有检查 `version` 字段（乐观锁），可能覆盖补偿调度器或其他并发操作刚写入的结果。

**场景**：
1. 用户 A 点击重新生成，查询到 `version=10`
2. 补偿调度器同时完成了一次成功分析，写入 `SUCCESS, version=11`
3. 用户 A 的请求继续执行，无条件覆盖写入 `PENDING, version=12`
4. **结果**：成功的分析结果被用户的重新生成请求覆盖

**影响**：
- 数据一致性破坏：丢失其他操作的成功结果
- 补偿调度器的努力被覆盖

**修复方案**：
```java
// DebugController.java 改进
MediaFile file = mediaFileMapper.selectById(id);
if (file == null) throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在，请检查后重试");

// 记录当前版本号
Integer currentVersion = file.getVersion();

// ... 幂等检查、限流等逻辑 ...

// 状态重置时使用乐观锁
int updated = mediaFileMapper.update(null, new LambdaUpdateWrapper<MediaFile>()
    .eq(MediaFile::getId, file.getId())
    .eq(MediaFile::getVersion, currentVersion)  // 乐观锁
    .set(MediaFile::getAiStatus, AiStatus.PENDING.name())
    .set(MediaFile::getAiSummary, null)
    .set(MediaFile::getAiProcessAt, LocalDateTime.now())
    .set(MediaFile::getAiAttempts, 0)
    .set(MediaFile::getCompensationAttempts, 0));

if (updated == 0) {
    // 版本冲突：说明记录已被其他操作修改
    contentTaskGate.rollbackSubmitting(contentHash);
    return Result.error(ErrorCode.CONFLICT, "文件状态已变更，请刷新后重试");
}
```

---

### ⚠️ P1 中等问题（重要）

#### 问题 3：补偿调度器与用户手动重试的计数冲突

**位置**：`AnalysisCompensationScheduler.java:137-159`

**问题描述**：
用户手动重试时会清零 `compensationAttempts`，但补偿调度器可能基于旧快照在清零后递增计数。

**场景**：
1. 文件处于 `PROCESSING`，`compensationAttempts=2`
2. 补偿调度器扫描到该文件，刷新时间戳成功（版本冲突检查通过）
3. **同时**用户点击"重新生成"，清零 `compensationAttempts=0`
4. 调度器触发 `aiService.asyncAnalyze` 完成后，读取最新记录 `compensationAttempts=0`
5. 递增为 1，写入数据库

**影响**：
- 用户期望从 0 重新开始，但调度器将其递增到 1
- 计数语义不清晰，可能导致误判重试次数

**修复方案**：
```java
// AnalysisCompensationScheduler.java 改进
private void incrementAttemptsIfStillPending(Long mediaId) {
    MediaFile latest = mediaFileMapper.selectById(mediaId);
    if (latest == null || !AiStatus.PROCESSING.name().equals(latest.getAiStatus())) {
        return;
    }
    
    // 新增：检查 aiProcessAt 是否在最近被刷新（说明可能是用户手动重试）
    if (latest.getAiProcessAt() != null && 
        Duration.between(latest.getAiProcessAt(), LocalDateTime.now()).getSeconds() < 5) {
        log.info("检测到最近的时间戳刷新，可能是用户手动重试，跳过计数 mediaId={}", mediaId);
        return;
    }
    
    // ... 原有递增逻辑 ...
}
```

**更优方案**：在 `MediaFile` 表新增 `lastRetryType` 字段，区分"补偿重试"和"用户手动重试"。

---

#### 问题 4：前端防重复点击不够强

**位置**：`ResultSidebar.vue:71`

**问题描述**：
`regenerating` 标志只在函数开始检查，但 `aiAnalyze/transcribe` 是异步的，用户在确认对话框期间快速双击仍可能发出多个请求。

**影响**：
- 短时间内发出多个重新生成请求
- 浪费 AI 配额
- 可能触发后端并发冲突

**修复方案**：
```javascript
// ResultSidebar.vue 改进
const regenerating = ref(false)
const requestInFlight = ref(false)  // 新增：请求飞行中标记

async function handleRegenerate() {
  if (regenerating.value || requestInFlight.value) return
  
  const confirmMessage = sidebar.value.type === 'ai'
    ? '重新生成将消耗 AI 配额，确定继续吗？'
    : '确定要重新提取文字吗？'
  
  const confirmed = await showConfirm(confirmMessage, '确认操作')
  if (!confirmed) return
  
  if (requestInFlight.value) return  // 确认对话框期间可能有其他请求
  
  regenerating.value = true
  requestInFlight.value = true
  
  try {
    if (sidebar.value.type === 'ai') {
      await aiAnalyze(sidebar.value.id, true)
    } else {
      await transcribe(sidebar.value.id, true)
    }
  } catch (error) {
    console.error('重新生成失败:', error)
    sidebar.value.content = '❌ 重新生成失败，请稍后重试'
    sidebar.value.loading = false
  } finally {
    regenerating.value = false
    setTimeout(() => { requestInFlight.value = false }, 1000)  // 防抖 1 秒
  }
}
```

---

### ℹ️ P2 轻微问题（优化）

#### 问题 5：跨用户内容复用时缓存未失效

**位置**：`AiService.java:332-335`

**问题描述**：
用户 A 重新生成时，会失效用户 A 的 Redis 缓存。但如果用户 B 上传了相同内容的文件并通过内容复用获得结果，用户 B 的缓存**不会**失效。

**影响**：
- 用户 B 可能看到过期的状态（如仍显示旧结果）
- 需要手动刷新页面

**修复方案**：
```java
// ContentTaskGate.java 改进
public boolean resolveAnalysis(MediaFile target, String contentHash) {
    // ... 原有复用逻辑 ...
    
    if (复用成功) {
        // 失效目标用户的缓存，确保前端能看到最新状态
        evictCache(target);
        return true;
    }
    return false;
}
```

---

### 修复优先级汇总

| 优先级 | 问题编号 | 问题描述 | 预计工作量 |
|--------|---------|---------|-----------|
| 🔴 P0 | 1 | force=true 跳过提交幂等键 | 1 小时 |
| 🔴 P0 | 2 | 用户重试加乐观锁 | 0.5 小时 |
| ⚠️ P1 | 3 | 补偿调度器计数冲突 | 1 小时 |
| ⚠️ P1 | 4 | 前端防重复点击加强 | 0.5 小时 |
| ℹ️ P2 | 5 | 跨用户缓存失效 | 0.5 小时 |

**总修复工作量**：3.5 小时

---

### 测试建议

**P0 修复后必须测试**：
1. **并发测试**：同一用户两个标签页同时重新生成 → 验证乐观锁
2. **竞态测试**：补偿调度器扫描期间用户点击重新生成 → 验证版本冲突检测
3. **幂等键测试**：force=true 时提交幂等键是否正确覆盖

**P1 修复后建议测试**：
4. **快速双击测试**：确认对话框期间快速双击 → 验证防重复逻辑
5. **调度器冲突测试**：模拟补偿调度器与用户重试的时间窗口冲突

---

## 总结

通过添加 `force` 参数实现重新生成功能，工作量 6 小时，成本 ¥150/月，不影响现有架构和其他用户。

**代码审查识别出 5 个问题，其中 2 个 P0 严重问题需立即修复，修复工作量 3.5 小时。**

**当前状态**：功能已实现，存在已知漏洞待修复  
**优先级**：高（P0 问题需优先处理）  
**预计完全上线**：2026-09-16（修复 P0 后）
