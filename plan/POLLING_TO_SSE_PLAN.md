# VideoCourseAI 通信机制改造方案：从轮询到 SSE

## 一、问题分析

### 1.1 当前轮询方案的问题

- **高延迟**：每 3 秒轮询一次，最坏情况下用户需等待 3 秒才能看到状态变化
- **高资源消耗**：每次轮询都获取整个列表，即使任务状态未变化
- **用户体验差**：无法感知任务进度细节（PENDING → PROCESSING 的变化）
- **兜底逻辑复杂**：10 轮 NONE 检测、10 分钟超时等硬编码逻辑

### 1.2 SSE 方案的优势

- **实时推送**：状态变化后立即推送，延迟 < 100ms
- **资源节约**：按需推送，无无效请求
- **分布式支持**：通过 Redis Pub/Sub 实现跨实例广播
- **自动重连**：客户端断线后指数退避重连（1s → 2s → 4s... 最大 15s）

---

## 二、核心设计

### 2.1 后端架构

#### 核心组件

**TaskEventService**（事件推送服务）
- 使用 `SseEmitter` 管理客户端连接
- 使用 `ConcurrentHashMap<String, List<SseEmitter>>` 作为连接池，key 为 `"type:mediaId"`
- 连接超时 30 分钟，避免僵尸连接

**Redis Pub/Sub**（跨实例广播）
- 频道：`videocourse:task-events`
- 消息格式：`{key: "type:mediaId", event: {state, transcriptText?, aiSummary?}}`
- 降级策略：Redis 不可用时自动降级为本地推送（单实例仍可用）

#### SSE 端点

```java
// DebugController 新增两个端点
GET /debug/ai-events?id={mediaId}          // AI 分析任务事件流
GET /debug/transcribe-events?id={mediaId}  // 文字提取任务事件流
```

**端点行为**：
1. 查询数据库获取当前状态和结果内容
2. 创建 SSE 连接并立即推送初始状态（携带完整数据）
3. 注册到连接池等待后续事件

#### 事件发布点

需要在**所有修改状态的地方**调用 `taskEventService.publishAnalysis/Transcription()`：

1. `VideoAnalysisConsumer` - MQ 消费者处理 AI 分析任务
2. `VideoAnalysisDlqConsumer` - 死信队列兜底标记失败
3. `AiService.asyncTranscribe()` - 文字提取服务
4. 补偿重试逻辑（如果存在）
5. 管理后台手动操作（如果有）

**验证方法**：
```bash
# 全局搜索所有状态变更点
grep -rn "setAiStatus\|aiStatus.*=" server/src --include="*.java"
grep -rn "setTranscriptStatus\|transcriptStatus.*=" server/src --include="*.java"
```

#### 事件数据结构

```java
public record TaskEvent(
    String state,           // 状态：NONE / PENDING / PROCESSING / SUCCESS / FAILED
    String transcriptText,  // 文字提取结果（仅 transcription 类型有值）
    String aiSummary        // AI 分析结果（仅 analysis 类型有值）
) {}
```

**为什么携带完整数据**：前端收到事件后直接更新本地状态，无需再次调用 `fetchList()`，减少请求量。

---

### 2.2 前端架构

#### 核心模块

**useTaskEvents.js**（SSE 连接管理）
- 维护连接池 `Map<key, {controller, id, type}>`
- 自动重连：指数退避，4xx 错误不重连
- 终态识别：`SUCCESS` / `FAILED` 后自动关闭连接
- 生命周期管理：组件卸载、侧边栏关闭时释放连接

**useMedia.js**（业务逻辑改造）
- 移除 `setInterval` 轮询逻辑
- `transcribe()` / `aiAnalyze()` 改为订阅 SSE
- SSE 事件到达后**直接更新本地列表状态**，无需 `fetchList()`

#### 状态更新优化

**改造前**（轮询）：
```javascript
setInterval(async () => {
  await fetchList()  // 每次都获取整个列表
}, 3000)
```

**改造后**（SSE）：
```javascript
taskStreams.start(id, type, path, (event) => {
  // 直接更新本地列表
  const item = list.value.find(i => i.id === id)
  if (item) {
    item.aiStatus = event.state
    item.aiSummary = event.aiSummary  // SSE 已携带完整数据
  }
})
```

---

## 三、实施步骤

### 3.1 实施前检查清单

开始编码前**务必确认**：

1. **核对状态枚举值**：确认数据库中实际使用的状态字符串与文档假设一致
2. **梳理所有状态变更点**：用上述 grep 命令找到所有修改状态的位置
3. **确认 Redis 可用性**：生产环境 Redis 部署方式（单机/集群/哨兵）
4. **确认网关配置**：如有 Nginx/API 网关，需调整超时与缓冲配置

---

### 3.2 后端改造

#### 步骤 1：创建核心服务

**新增文件**：`TaskEventService.java`（约 150 行）
- 实现 `MessageListener` 接口监听 Redis Pub/Sub
- 提供 `subscribe()` / `publishAnalysis()` / `publishTranscription()` 方法
- 连接池管理和自动清理逻辑

**关键点**：
- `subscribe()` 方法立即推送初始状态（查询数据库获取当前状态和内容）
- `publish()` 方法先通过 Redis 广播，失败时降级到本地推送
- 终态事件（`SUCCESS` / `FAILED`）自动调用 `emitter.complete()`

#### 步骤 2：配置 Redis 监听器

**新增文件**：`TaskEventRedisConfig.java`（约 20 行）
- 创建 `RedisMessageListenerContainer`
- 订阅频道 `videocourse:task-events`
- 将消息路由到 `TaskEventService`

#### 步骤 3：添加 SSE 端点

**修改文件**：`DebugController.java`
- 注入 `TaskEventService`
- 新增 `/debug/ai-events` 和 `/debug/transcribe-events` 端点
- 端点返回类型：`MediaType.TEXT_EVENT_STREAM_VALUE`

#### 步骤 4：改造业务层

**需修改的文件**：
- `VideoAnalysisConsumer.java` - 在更新状态后调用 `publishAnalysis()`
- `VideoAnalysisDlqConsumer.java` - 标记失败时调用 `publishAnalysis()`
- `AiService.java` - 在 `asyncTranscribe()` 中调用 `publishTranscription()`

**注意**：每次调用 `publish*()` 时传入完整数据（状态 + 内容），而不仅仅是状态。

#### 步骤 5：添加启动检查

在 `TaskEventService` 中添加 `@PostConstruct` 方法检查 Redis 连接：
```java
@PostConstruct
public void checkRedisConnection() {
    try {
        redisTemplate.convertAndSend(REDIS_CHANNEL, "ping");
        log.info("✅ Redis Pub/Sub 配置正常");
    } catch (Exception e) {
        log.warn("⚠️ Redis Pub/Sub 不可用，SSE 降级为单实例模式", e);
    }
}
```

---

### 3.3 前端改造

#### 步骤 1：创建 SSE 管理模块

**新增文件**：`useTaskEvents.js`（约 150 行）
- 导出 `createTaskStreams()` 函数，返回 `{start, stop, stopAll}`
- 实现 SSE 流解析、重连逻辑、终态识别

**关键点**：
- 使用 `AbortController` 管理连接生命周期
- 4xx 错误不重连，直接释放连接
- 指数退避：`Math.min(15_000, 1_000 * 2 ** reconnectAttempt)`

#### 步骤 2：修改 API 层

**修改文件**：`api/index.js`
- 导出 `apiRequest()` 函数供 SSE 使用
- 新增 `subscribeAiEvents()` / `subscribeTranscribeEvents()` 返回端点路径

#### 步骤 3：改造业务逻辑

**修改文件**：`useMedia.js`
- 移除所有 `setInterval` 轮询代码
- 在 `transcribe()` / `aiAnalyze()` 中调用 `startSSEStream()`
- SSE 回调中直接更新本地列表状态，无需 `fetchList()`
- `closeSidebar()` 时调用 `taskStreams.stop()` 释放连接

---

### 3.4 Nginx 配置（如有）

如果使用 Nginx 反向代理，需要为 SSE 端点添加专用配置：

```nginx
location ~ ^/debug/(ai-events|transcribe-events) {
    proxy_pass http://backend;
    
    # SSE 必需配置
    proxy_buffering off;
    proxy_cache off;
    proxy_http_version 1.1;
    proxy_set_header Connection '';
    chunked_transfer_encoding on;
    
    # 超时 30 分钟
    proxy_read_timeout 30m;
    proxy_connect_timeout 10s;
    proxy_send_timeout 30m;
}
```

**验证**：
```bash
curl -N -H "Accept: text/event-stream" "http://localhost:9090/debug/ai-events?id=1"
```

---

## 四、测试清单

### 4.1 基础功能
- [ ] 单实例：提交任务 → SSE 推送 → 侧边栏实时更新
- [ ] 多实例：实例 A 提交，实例 B 的订阅者也收到事件
- [ ] 重连：断开连接后自动指数退避重连
- [ ] 终态：任务完成后 SSE 自动关闭，不再重连

### 4.2 状态流转
- [ ] AI 分析：NONE → PENDING → PROCESSING → SUCCESS
- [ ] 文字提取：NONE → PROCESSING → SUCCESS
- [ ] 失败场景：任务失败时正确推送 FAILED
- [ ] 死信兜底：死信队列处理后正确推送 FAILED

### 4.3 前端交互
- [ ] 快速切换不同任务，旧连接正确关闭
- [ ] 刷新页面后重新订阅，正确获取当前状态
- [ ] 登出时所有 SSE 连接正确关闭
- [ ] 本地状态更新无需 fetchList

### 4.4 压力测试
- [ ] 网络抖动：频繁断线重连时服务端正确清理旧连接
- [ ] 内存泄漏：大量连接断开后连接池正确清空
- [ ] 长连接超时：30 分钟后自动超时并重连
- [ ] 4xx 错误：文件不存在返回 404，客户端不重连

### 4.5 降级与容错
- [ ] Redis 故障：Redis 宕机后降级到本地推送（单实例仍可用）
- [ ] 并发订阅：同一用户在多个标签页打开同一视频，各自独立订阅

---

## 五、灰度上线方案

### 5.1 阶段 1：灰度测试

**目标**：新增 SSE 功能，保留轮询作为兜底。

**后端**：完成所有改造，SSE 和轮询接口并存。

**前端**：添加灰度开关
```javascript
// 基于用户 ID 的稳定灰度（20%）
const USE_SSE = (currentUser.value?.id || 0) % 100 < 20

// 在 transcribe/aiAnalyze 中分支
if (USE_SSE) {
  startSSEStream(id, type)
} else {
  startPolling(id, type)  // 保留的轮询逻辑
}
```

**监控指标**：
- SSE 推送延迟（目标 < 100ms）
- SSE 重连次数（目标 < 0.1%）
- 服务端连接数峰值
- Redis Pub/Sub 消息量
- 错误率对比（SSE vs 轮询）

### 5.2 阶段 2：全量切换

**前提**：灰度无严重问题，SSE 成功率 > 99%。

**操作**：
1. 灰度比例调整为 100%：`const USE_SSE = true`
2. 观察 2-3 天，确保稳定
3. 删除轮询代码（`startPolling` 函数、灰度分支）
4. 保留状态查询接口（页面刷新时使用）

### 5.3 阶段 3：清理优化

**清理项**：
- 移除轮询相关的兜底逻辑（10 轮 NONE 检测、10 分钟超时）
- 删除 `pollingTimers` 相关代码
- 清理灰度开关和分支逻辑

**优化项**：
- 添加 SSE 连接数监控告警
- 添加 Redis Pub/Sub 消息量监控
- 记录 SSE 推送延迟到日志/监控系统

---

## 六、注意事项

### 6.1 状态映射一致性

⚠️ **务必确认数据库实际使用的状态值**，避免终态判断失效。

文档假设的状态集合：
- `aiStatus`: `NONE` / `PENDING` / `PROCESSING` / `SUCCESS` / `FAILED`
- `transcriptStatus`: `NONE` / `PROCESSING` / `SUCCESS` / `FAILED`

如果实际数据库使用不同的值（如 `COMPLETE` 而非 `SUCCESS`），需要调整：
- `TaskEventService.isTerminal()` 方法
- 前端 SSE 回调中的终态判断

### 6.2 事件发布完整性

使用以下命令检查是否有遗漏的状态变更点：
```bash
grep -rn "setAiStatus\|aiStatus.*=" server/src --include="*.java"
grep -rn "setTranscriptStatus\|transcriptStatus.*=" server/src --include="*.java"
```

**重点关注**：
- 死信队列消费者
- 补偿重试定时任务
- 管理后台手动操作接口

### 6.3 Redis 依赖

SSE 的跨实例广播依赖 Redis Pub/Sub：
- 确保 Redis 高可用（哨兵/集群）
- Redis 不可用时自动降级到本地推送（单实例仍可用）
- 启动时检查 Redis 连接并记录日志

### 6.4 浏览器兼容性

SSE 不支持 IE，但现代浏览器（Chrome/Firefox/Safari/Edge）均支持。
如需支持 IE，考虑降级到轮询或使用 WebSocket。

### 6.5 连接数限制

SSE 是长连接，建议：
- 配置 Nginx `proxy_read_timeout` 为 30 分钟
- 监控服务端连接数，设置告警阈值
- 客户端做好连接池管理，避免泄漏

---

## 七、回滚方案

如果上线后发现严重问题，可快速回滚：

**前端回滚**：
1. 将灰度开关改为 `const USE_SSE = false`
2. 或直接注释掉 SSE 相关代码，恢复轮询逻辑

**后端回滚**：
- SSE 端点不影响原有功能，可以保留
- 如需彻底移除，删除 `TaskEventService` 和 `TaskEventRedisConfig`

**验证**：回滚后确认轮询逻辑正常工作。

---

## 八、总结

### 改造收益

| 维度 | 轮询（改造前） | SSE（改造后） |
|------|---------------|---------------|
| 延迟 | 最高 3 秒 | < 100ms |
| 资源消耗 | 高（每 3s 刷新列表） | 低（按需推送） |
| 用户体验 | 转圈等待 | 实时阶段感知 |
| 服务器压力 | 大量无效请求 | 长连接维护 |
| 分布式支持 | 无 | Redis Pub/Sub |

### 工作量估算

- 后端开发：2-3 天（核心服务 + 业务层改造）
- 前端开发：1-2 天（SSE 管理 + 业务逻辑改造）
- 测试验证：2-3 天（功能测试 + 压力测试）
- 灰度上线：1-2 周（观察监控指标）
- **总计**：2-3 周

### 核心要点

1. **事件携带完整数据**：减少前端 `fetchList()` 调用
2. **Redis 降级策略**：保证单实例场景可用性
3. **全面梳理发布点**：确保所有状态变更都推送事件
4. **灰度上线**：保留轮询作为兜底，逐步切换
5. **终态自动关闭**：避免僵尸连接和无效重连
