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

## 总结

通过添加 `force` 参数实现重新生成功能，工作量 6 小时，成本 ¥150/月，不影响现有架构和其他用户。

**状态**：待实现  
**优先级**：高  
**预计上线**：2026-09-16
