# Phase 7 清理记录

## 已注释的轮询代码位置

### client/src/composables/useMedia.js

| 行号范围 | 内容 | 说明 |
|---------|------|------|
| 12 | `const pollingTimers = ref({})` | 轮询定时器状态管理 |
| 233-312 | `function startPolling(id, type) { ... }` | 完整的轮询逻辑（80行） |

**注释标记**: `[Phase 7 - 待删除]`

## 待删除内容清单

✅ 已注释但未删除（等待用户确认测试通过）：
1. **pollingTimers 状态** (第12行)
   - 用途：存储各任务的轮询定时器
   - 依赖：startPolling 函数
   
2. **startPolling 函数** (第233-312行)
   - 功能：
     - 3秒轮询刷新列表
     - 未启动兜底（连续10轮NONE判定失败）
     - 10分钟超时兜底
   - 已被 `startSSEStream` 完全替代

## 保留的代码

✅ **继续使用 SSE**：
- `startSSEStream(id, type)` - SSE 实时推送 (第182-231行)
- `taskStreams` - SSE 连接管理器 (第13行)
- `closeSidebar()` 中的 SSE 连接清理逻辑 (第323-332行)

## 用户测试检查清单

请测试以下场景确认 SSE 完全替代轮询：

- [ ] 上传新文件并提交 AI 分析，状态实时更新
- [ ] 提交文字提取任务，状态实时更新
- [ ] 快速切换不同文件，旧连接正确关闭
- [ ] 刷新页面后重新打开侧边栏，状态正确显示
- [ ] 任务完成后侧边栏显示结果内容
- [ ] 任务失败时显示错误提示
- [ ] 浏览器开发者工具无报错

## 测试通过后的删除步骤

用户确认测试通过后，执行以下删除：

```javascript
// 1. 删除第12行
- const pollingTimers = ref({})

// 2. 删除第233-312行（整个 startPolling 函数及注释）
- function startPolling(id, type) { ... }
```

## 备注

- 轮询逻辑已在 Phase 4 时被 SSE 替代，目前所有场景均使用 `startSSEStream`
- 注释保留是为了灰度期间的快速回滚能力（如需回滚只需取消注释）
- 测试通过后可安全删除，不影响任何功能
