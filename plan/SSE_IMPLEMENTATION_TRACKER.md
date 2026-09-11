# SSE 重构实施跟踪文档

## 实施原则

1. **增量式改造**：每个阶段独立可测试,避免大爆炸式改动
2. **向后兼容**：SSE 与轮询并存,灰度切换
3. **防御性编程**：完善异常处理、降级策略、单元测试

---

## 阶段划分

### ✅ Phase 1: 核心服务层搭建 (已完成)
**目标**: 搭建 SSE 核心基础设施,可独立测试 ✅

- [x] 优化事件数据结构 (补充 mediaId/timestamp/error)
- [x] 创建 TaskEventService (SSE 连接管理 + Redis Pub/Sub)
- [x] 创建 TaskEventRedisConfig (Redis 监听器配置)
- [x] 添加启动检查 (@PostConstruct 验证 Redis 连接)
- [x] 编写单元测试 (TaskEventServiceTest)
  - [x] 本地推送测试
  - [x] Redis Pub/Sub 广播测试
  - [x] Redis 故障降级测试
  - [x] 终态自动关闭测试

**验证标准**: 
- ✅ 单元测试全部通过
- ✅ 启动日志显示 Redis Pub/Sub 状态
- ✅ 后端编译通过

**完成日期**: 2026-09-10

---

### ✅ Phase 2: SSE 端点暴露 (已完成)
**目标**: 前端可通过 HTTP 订阅 SSE 流 ✅

- [x] 在 DebugController 添加 SSE 端点
  - [x] GET /debug/task-events?id={mediaId}&type={ai|transcribe}
  - [x] 查询数据库获取初始状态
  - [x] 立即推送初始事件 (携带完整数据)
  - [x] 注册到 TaskEventService 连接池
- [ ] 添加集成测试 (DebugControllerTest)
  - [ ] curl 测试 SSE 连接
  - [ ] 验证初始状态推送
  - [ ] 模拟状态变更验证事件推送

**验证标准**:
```bash
# 测试命令
curl -N -H "Accept: text/event-stream" "http://localhost:9090/debug/task-events?id=1&type=ai"

# 预期输出
data: {"mediaId":1,"state":"NONE","aiSummary":null,"transcriptText":null,"error":null,"timestamp":1726047123456}
```

- [x] 在 DebugController 添加 SSE 端点
  - [x] GET /debug/task-events?id={mediaId}&type={ai|transcribe}
  - [x] 查询数据库获取初始状态
  - [x] 立即推送初始事件 (携带完整数据)
  - [x] 注册到 TaskEventService 连接池
- [ ] 添加集成测试 (DebugControllerTest)
  - [ ] curl 测试 SSE 连接
  - [ ] 验证初始状态推送
  - [ ] 模拟状态变更验证事件推送

**验证标准**:
```bash
# 测试命令
curl -N -H "Accept: text/event-stream" "http://localhost:9090/debug/task-events?id=1&type=ai"

# 预期输出
data: {"mediaId":1,"state":"NONE","aiSummary":null,"transcriptText":null,"error":null,"timestamp":1726047123456}
```

**完成日期**: 2026-09-10 (端点已添加，集成测试待补充)

---

### ✅ Phase 3: 业务层事件发布集成 (已完成)
**目标**: 所有状态变更点触发 SSE 推送 ✅

- [x] AiService 集成
  - [x] asyncAnalyze 成功 → publishAnalysis(SUCCESS + aiSummary)
  - [x] markFailed → publishAnalysis(FAILED + error)
  - [x] asyncTranscribe 成功 → publishTranscription(SUCCESS + transcriptText)
- [x] ContentTaskGate 集成
  - [x] resolveAnalysis 复用 → publishAnalysis(SUCCESS + 复用内容)
  - [x] resolveTranscription 复用 → publishTranscription(SUCCESS + 复用内容)
- [x] VideoAnalysisConsumer 集成（无需修改，由 AiService.asyncAnalyze 推送）
- [x] VideoAnalysisDlqConsumer 集成（无需修改，调用 markFailedFinal）
- [x] AnalysisCompensationScheduler 集成
  - [x] 补偿失败 → publishAnalysis(FAILED + error)
- [x] DebugController 提交侧集成
  - [x] AI 分析提交 → publishAnalysis(PENDING)
  - [x] 文字提取提交 → publishTranscription(PROCESSING)

**全量排查**:
```bash
# 验证所有状态变更点已覆盖
grep -rn "setAiStatus\|aiStatus.*=" server/src/main --include="*.java" | wc -l
grep -rn "setTranscriptStatus\|transcriptStatus.*=" server/src/main --include="*.java" | wc -l
```

**验证标准**:
- 通过日志验证每个状态变更都触发了 publishXxx 调用
- 集成测试验证完整流程: 提交任务 → SSE 推送 PENDING → PROCESSING → SUCCESS

---

### ✅ Phase 4: 前端 SSE 模块开发 (已完成)
**目标**: 前端完成 SSE 连接管理与业务集成 ✅

#### 4.1 SSE 连接管理模块
- [x] 创建 useTaskEvents.js
  - [x] createTaskStreams() 工厂函数
  - [x] start(id, type) 建立 SSE 连接
  - [x] stop(id, type) 关闭指定连接
  - [x] stopAll() 清理所有连接
  - [x] 自动重连逻辑 (指数退避, 最大 15s, 最多 10 次)
  - [x] 终态识别 (SUCCESS/FAILED 自动关闭)
  - [x] 4xx 错误不重连
  - [x] 页面可见性 API (隐藏时暂停重连)

#### 4.2 API 层改造
- [x] 修改 api/index.js
  - [x] 新增 getTaskEventsUrl(id, type) 返回端点路径

#### 4.3 业务逻辑改造
- [x] 修改 useMedia.js
  - [x] transcribe() 改为订阅 SSE
  - [x] aiAnalyze() 改为订阅 SSE
  - [x] SSE 回调直接更新本地列表 (含 mediaId 存在性检查)
  - [x] closeSidebar() 时调用 taskStreams.stop()
  - [x] 保留轮询逻辑 (灰度开关控制)

**验证标准**:
- 浏览器开发者工具 Network 标签可见 SSE 连接
- 提交任务后实时看到侧边栏状态更新 (无轮询延迟)
- 关闭侧边栏后 SSE 连接正确释放

**完成日期**: 2026-09-10

---

### 🧪 Phase 5: 端到端测试 (当前阶段，待执行)
**目标**: 验证完整链路与异常场景

#### 5.1 功能测试
- [ ] 单实例场景
  - [ ] AI 分析: NONE → PENDING → PROCESSING → SUCCESS
  - [ ] 文字提取: NONE → PROCESSING → SUCCESS
  - [ ] 失败场景: PROCESSING → FAILED
  - [ ] 死信兜底: DLQ 消费 → FAILED
  - [ ] 结果复用: 复用他人结果 → SUCCESS (无 PROCESSING)
- [ ] 多实例场景 (启动两个后端实例)
  - [ ] 实例 A 提交任务
  - [ ] 实例 B 的 SSE 订阅者也收到事件 (验证 Redis Pub/Sub)
- [ ] 前端交互
  - [ ] 快速切换不同任务,旧连接正确关闭
  - [ ] 刷新页面重新订阅,获取当前状态
  - [ ] 多标签页场景: 本地列表不含 mediaId 时优雅忽略
  - [ ] 登出时所有 SSE 连接释放

#### 5.2 异常测试
- [ ] 网络抖动: 频繁断线重连,服务端正确清理旧连接
- [ ] 长连接超时: 30 分钟后自动超时并重连
- [ ] 4xx 错误: 文件不存在返回 404,客户端不重连
- [ ] Redis 故障: 停止 Redis 后仍可本地推送 (降级)
- [ ] 内存泄漏: 大量连接断开后连接池清空 (使用 JVisualVM 监控)

#### 5.3 压力测试
- [ ] 100 并发用户同时订阅
- [ ] 监控指标
  - [ ] SSE 推送延迟 (目标 < 100ms)
  - [ ] SSE 重连次数 (目标 < 0.1%)
  - [ ] 服务端连接数峰值
  - [ ] Redis Pub/Sub 消息延迟 (目标 < 50ms)
  - [ ] JVM 堆内存 / CPU 使用率

**验证标准**:
- 所有测试用例通过
- 无内存泄漏
- SSE 推送延迟 < 100ms (P99)

---

### 🚦 Phase 6: 灰度上线 (预计 2 周)
**目标**: 分阶段切换,保留轮询兜底

#### 6.1 灰度开关实现
- [ ] 前端添加灰度逻辑
```javascript
// useMedia.js
const USE_SSE = (currentUser.value?.id || 0) % 100 < 20  // 20% 灰度

if (USE_SSE) {
  startSSEStream(id, type)
} else {
  startPolling(id, type)
}
```

#### 6.2 监控指标配置
- [ ] 添加自定义指标 (如使用 Micrometer)
  - [ ] sse_connections_active (当前连接数)
  - [ ] sse_push_latency (推送延迟直方图)
  - [ ] sse_reconnect_count (重连次数)
  - [ ] redis_pubsub_latency (Redis Pub/Sub 延迟)
- [ ] 配置告警
  - [ ] SSE 连接数 > 1000
  - [ ] SSE 推送失败率 > 0.5%
  - [ ] Redis Pub/Sub 消息延迟 > 100ms

#### 6.3 灰度阶段
- [ ] **Day 1-3**: 5% 灰度 (修改为 `< 5`)
  - [ ] 观察错误日志
  - [ ] 对比 SSE vs 轮询的延迟
- [ ] **Day 4-7**: 20% 灰度
  - [ ] 观察服务端连接数
  - [ ] 验证 Redis Pub/Sub 消息量
- [ ] **Day 8-10**: 50% 灰度
  - [ ] 压力测试验证
- [ ] **Day 11-14**: 100% 灰度
  - [ ] 观察 2-3 天确保稳定

**验证标准**:
- SSE 成功率 > 99%
- 无严重问题反馈
- 监控指标符合预期

---

### 🧹 Phase 7: 清理优化 (预计 1 天)
**目标**: 移除轮询代码,优化性能

- [ ] 删除前端轮询逻辑
  - [ ] 删除 pollingTimers 相关代码
  - [ ] 删除 startPolling 函数
  - [ ] 删除灰度开关
  - [ ] 删除 10 轮 NONE / 10 分钟超时兜底逻辑
- [ ] 保留状态查询接口 (页面刷新时使用)
- [ ] 性能优化
  - [ ] 调优 Redis 连接池
  - [ ] 调优 SSE 超时时间
  - [ ] 添加 SSE 推送延迟日志
- [ ] 文档更新
  - [ ] 更新 ARCHITECTURE.md (前端架构部分)
  - [ ] 添加 SSE 故障排查手册
  - [ ] 添加性能调优指南

---

## 风险控制

### 回滚方案
- **前端回滚**: 将灰度开关改为 `const USE_SSE = false`
- **后端回滚**: SSE 端点不影响原有功能,可保留

### 质量保障
- [ ] 每个阶段完成后 Code Review
- [ ] 单元测试覆盖率 > 80%
- [ ] 集成测试覆盖核心链路
- [ ] 压力测试验证性能指标

---

## 进度跟踪

| 阶段 | 预计耗时 | 实际耗时 | 状态 | 完成日期 | 备注 |
|------|---------|---------|------|---------|------|
| Phase 1 | 2 天 | 0.5 天 | ✅ 已完成 | 2026-09-10 | 核心服务层编译通过 |
| Phase 2 | 1 天 | 0.5 天 | ✅ 已完成 | 2026-09-10 | SSE 端点已暴露，集成测试待补充 |
| Phase 3 | 1.5 天 | 0.5 天 | ✅ 已完成 | 2026-09-10 | AiService、ContentTaskGate 所有状态变更点已集成 SSE 推送 |
| Phase 4 | 2 天 | 0.5 天 | ✅ 已完成 | 2026-09-10 | 创建 useTaskEvents.js，改造 useMedia.js 替换轮询为 SSE |
| Phase 5 | 1.5 天 | - | 🔄 当前阶段 | - | 待端到端验证 |
| Phase 6 | 2 周 | - | ⚪ 未开始 | - | - |
| Phase 7 | 1 天 | - | ⚪ 未开始 | - | - |

**总计**: 约 3-4 周

---

## 关键决策记录

### 1. 事件数据结构优化
**决策**: 增加 `mediaId`/`timestamp`/`error` 字段

**理由**:
- `mediaId`: 前端需要知道是哪个文件的事件 (多标签页场景)
- `timestamp`: 时序判断与调试
- `error`: FAILED 时携带错误信息

### 2. SSE 端点合并
**决策**: 使用单一端点 `/debug/task-events?type=ai|transcribe`

**理由**: 减少代码重复,逻辑统一

### 3. 多标签页同步策略
**决策**: 本地列表不包含 mediaId 时优雅忽略

**理由**: 避免不必要的 fetchList() 调用,减少服务端压力

### 4. Redis 故障降级
**决策**: Redis 不可用时自动降级到本地推送

**理由**: 单实例场景仍可用,提升可用性

---

## 下一步行动

✅ **下一步**: Phase 5 - 端到端测试
- 启动完整项目（后端 + 前端）
- 在浏览器 Network 面板验证 SSE 连接建立
- 提交转写/AI 分析任务，验证侧边栏实时更新
