# VideoCourseAI 通信机制改造方案：从轮询到 SSE

## 一、两个项目的通信机制对比

### 1.1 DOVideo-AI-main（SSE 实现）

**前端实现**：
- 文件：`client/src/taskEvents.js`
- 核心机制：**Server-Sent Events (SSE)** 单向推送
- 连接管理：连接池 `createTaskStreams()`，支持多任务并发订阅
- 重连策略：指数退避（1s → 2s → 4s... 最大 15s）
- 终态识别：HTTP 4xx 错误（404/403/400）不重连，直接释放连接
- 事件解析：标准 SSE 协议（`data:` 前缀 + JSON 解析）

**后端实现**：
- 文件：`server/src/main/java/com/example/server/service/TaskEventService.java`
- 核心组件：`SseEmitter` + Redis Pub/Sub
- 订阅接口：
  - `/analysis/transcription-events?id={mediaId}` - 转写任务
  - `/analysis/analysis-events?id={mediaId}&goal={goal}&mode={mode}` - AI 分析任务
- 事件发布：业务代码调用 `TaskEventService.publishAnalysis()` / `publishTranscription()`
- 跨实例同步：Redis Pub/Sub 频道 `dovideo:task-events`，确保多节点下所有订阅者都能收到事件
- 自动完成：终态事件（`COMPLETED`/`FAILED`）自动调用 `emitter.complete()`

**关键特性**：
- 实时推送，无轮询开销
- 支持分布式部署（通过 Redis 跨节点广播）
- 自动重连 + 终态识别，用户体验丝滑
- 连接超时 30 分钟，避免僵尸连接

---

### 1.2 VideoCourseAI-main（轮询实现）

**前端实现**：
- 文件：`client/src/composables/useMedia.js`
- 核心机制：**定时轮询**（`setInterval` 每 3 秒刷新列表）
- 问题点：
  - 每次轮询都调用 `fetchList()` 获取整个列表（冗余请求）
  - 10 轮 NONE 状态兜底检测任务未启动
  - 10 分钟强制超时停止轮询
  - 无法感知任务阶段变化（PENDING → PROCESSING）

**后端实现**：
- 文件：`server/src/main/java/com/example/server/controller/DebugController.java`
- 核心机制：状态字段轮询（`aiStatus` / `transcriptStatus`）
- 无主动推送能力，依赖前端定时查询数据库状态

**问题总结**：
- 高延迟：最坏情况下需等待 3 秒才能看到状态变化
- 高资源消耗：大量无效请求（任务未变化时仍在刷新）
- 用户体验差：转圈等待时无法感知任务进度细节

---

## 二、改造方案

### 2.1 后端改造（核心）

#### 2.1.1 新增依赖（已有，无需修改）
`pom.xml` 已包含 `spring-boot-starter-data-redis`，无需额外依赖。

---

#### 2.1.2 创建 `TaskEventService`（事件推送核心）

**新增文件**：`server/src/main/java/com/example/server/service/TaskEventService.java`

```java
package com.example.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务事件推送服务（SSE）
 * 支持 AI 分析、文字提取任务的实时状态推送
 */
@Slf4j
@Service
public class TaskEventService implements MessageListener {

    public static final String ANALYSIS = "analysis";
    public static final String TRANSCRIPTION = "transcription";
    public static final String REDIS_CHANNEL = "videocourse:task-events";
    
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L; // 30 分钟超时

    // 订阅者连接池：key = "type:mediaId"
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> subscribers = 
            new ConcurrentHashMap<>();
    
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public TaskEventService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 订阅任务事件流
     * @param mediaId 媒体 ID
     * @param type 任务类型（analysis / transcription）
     * @param initialStatus 初始状态（立即推送）
     * @return SSE 连接
     */
    public SseEmitter subscribe(Long mediaId, String type, String initialStatus) {
        String key = key(mediaId, type);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        
        // 注册连接
        subscribers.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);
        
        // 连接生命周期管理
        emitter.onCompletion(() -> remove(key, emitter));
        emitter.onTimeout(() -> remove(key, emitter));
        emitter.onError(error -> remove(key, emitter));
        
        // 立即推送初始状态
        send(key, emitter, new TaskEvent(initialStatus));
        
        return emitter;
    }

    /**
     * 发布 AI 分析任务状态变更
     */
    public void publishAnalysis(Long mediaId, String status) {
        publish(key(mediaId, ANALYSIS), new TaskEvent(status));
    }

    /**
     * 发布文字提取任务状态变更
     */
    public void publishTranscription(Long mediaId, String status) {
        publish(key(mediaId, TRANSCRIPTION), new TaskEvent(status));
    }

    /**
     * 发布事件到 Redis Pub/Sub（跨实例广播）
     */
    private void publish(String key, TaskEvent event) {
        try {
            String payload = objectMapper.createObjectNode()
                    .put("key", key)
                    .set("event", objectMapper.valueToTree(event))
                    .toString();
            Long receivers = redisTemplate.convertAndSend(REDIS_CHANNEL, payload);
            
            // Redis 不可用或无订阅者时，本地降级发送
            if (receivers == null || receivers == 0) {
                publishLocal(key, event);
            }
        } catch (RuntimeException e) {
            log.warn("Redis 发布失败，降级本地推送 key={}", key, e);
            publishLocal(key, event);
        }
    }

    /**
     * Redis Pub/Sub 消息监听（跨实例接收）
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode payload = objectMapper.readTree(message.getBody());
            publishLocal(
                    payload.path("key").asText(),
                    objectMapper.treeToValue(payload.path("event"), TaskEvent.class)
            );
        } catch (Exception e) {
            log.warn("Redis 消息解析失败", e);
        }
    }

    /**
     * 本地推送给当前实例的订阅者
     */
    private void publishLocal(String key, TaskEvent event) {
        List<SseEmitter> emitters = subscribers.get(key);
        if (emitters == null) return;
        
        emitters.forEach(emitter -> send(key, emitter, event));
    }

    /**
     * 单个连接推送事件
     */
    private void send(String key, SseEmitter emitter, TaskEvent event) {
        try {
            emitter.send(SseEmitter.event().name("task-status").data(event));
            
            // 终态自动完成连接
            if (isTerminal(event.state())) {
                remove(key, emitter);
                emitter.complete();
            }
        } catch (IOException | IllegalStateException e) {
            remove(key, emitter);
            emitter.completeWithError(e);
            log.debug("SSE 连接已关闭 key={}", key);
        }
    }

    /**
     * 移除订阅者
     */
    private void remove(String key, SseEmitter emitter) {
        subscribers.computeIfPresent(key, (k, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    private String key(Long mediaId, String type) {
        return type + ":" + mediaId;
    }

    private boolean isTerminal(String status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status);
    }

    /**
     * 任务事件 DTO
     */
    public record TaskEvent(String state) {}
}
```

---

#### 2.1.3 配置 Redis 监听器

**新增文件**：`server/src/main/java/com/example/server/config/TaskEventRedisConfig.java`

```java
package com.example.server.config;

import com.example.server.service.TaskEventService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class TaskEventRedisConfig {

    @Bean
    public RedisMessageListenerContainer taskEventListenerContainer(
            RedisConnectionFactory connectionFactory,
            TaskEventService taskEventService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                taskEventService, 
                new ChannelTopic(TaskEventService.REDIS_CHANNEL)
        );
        return container;
    }
}
```

---

#### 2.1.4 修改 `DebugController` 增加 SSE 端点

在 `DebugController.java` 中新增两个方法：

```java
// 新增依赖注入
private final TaskEventService taskEventService;

public DebugController(/*...已有参数...*/, TaskEventService taskEventService) {
    // ...已有代码...
    this.taskEventService = taskEventService;
}

/**
 * AI 分析任务事件流（SSE）
 */
@GetMapping(value = "/ai-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter aiAnalysisEvents(@RequestParam Long id) {
    MediaFile file = mediaFileMapper.selectById(id);
    if (file == null) {
        throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
    }
    
    String currentStatus = file.getAiStatus() == null ? "NONE" : file.getAiStatus();
    return taskEventService.subscribe(id, TaskEventService.ANALYSIS, currentStatus);
}

/**
 * 文字提取任务事件流（SSE）
 */
@GetMapping(value = "/transcribe-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter transcribeEvents(@RequestParam Long id) {
    MediaFile file = mediaFileMapper.selectById(id);
    if (file == null) {
        throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
    }
    
    String currentStatus = file.getTranscriptStatus() == null ? "NONE" : file.getTranscriptStatus();
    return taskEventService.subscribe(id, TaskEventService.TRANSCRIPTION, currentStatus);
}
```

---

#### 2.1.5 修改消费者和服务层发布事件

**在 `VideoAnalysisConsumer.java` 中**（AI 分析消费者）：

```java
// 注入 TaskEventService
private final TaskEventService taskEventService;

// 在状态变更处发布事件
private void updateStatusAndPublish(Long mediaId, String status) {
    // 原有的数据库更新代码...
    mediaFileMapper.update(/*...*/);
    
    // 新增：发布 SSE 事件
    taskEventService.publishAnalysis(mediaId, status);
}
```

**在 `AiService.asyncTranscribe()` 中**（文字提取服务）：

```java
// 注入 TaskEventService
private final TaskEventService taskEventService;

// 在状态变更处发布事件
public void asyncTranscribe(Long mediaId) {
    try {
        // 开始转写
        taskEventService.publishTranscription(mediaId, "PROCESSING");
        
        // ... 转写逻辑 ...
        
        // 成功
        taskEventService.publishTranscription(mediaId, "SUCCESS");
    } catch (Exception e) {
        // 失败
        taskEventService.publishTranscription(mediaId, "FAILED");
    }
}
```

---

### 2.2 前端改造

#### 2.2.1 新增 SSE 连接管理模块

**新增文件**：`client/src/composables/useTaskEvents.js`

```javascript
import { apiRequest } from '../api/index.js'

/**
 * SSE 任务事件流管理
 */
export function createTaskStreams({ onActiveChange = () => {} } = {}) {
  const streams = new Map()
  const keyOf = (id, type) => `${type}:${id}`

  const publish = () => onActiveChange([...streams.values()]
    .map(({ id, type }) => ({ id, type })))

  const stop = (id, type) => {
    const key = keyOf(id, type)
    const entry = streams.get(key)
    if (!entry) return
    entry.controller.abort()
    streams.delete(key)
    publish()
  }

  const stopAll = () => {
    if (!streams.size) return
    for (const { controller } of streams.values()) controller.abort()
    streams.clear()
    publish()
  }

  const start = (id, type, path, onEvent, onError) => {
    stop(id, type)
    const key = keyOf(id, type)
    const controller = new AbortController()
    streams.set(key, { controller, id, type })
    publish()
    let reconnectAttempt = 0

    const release = () => {
      if (streams.get(key)?.controller !== controller) return
      streams.delete(key)
      publish()
    }

    const run = async () => {
      while (!controller.signal.aborted && streams.get(key)?.controller === controller) {
        try {
          const response = await apiRequest(path, {
            headers: { Accept: 'text/event-stream' },
            signal: controller.signal
          })
          
          if (!response.ok) {
            const error = new Error(`事件流连接失败（HTTP ${response.status}）`)
            error.status = response.status
            
            // 4xx 错误不重连
            if (response.status >= 400 && response.status < 500) {
              release()
              onError?.(error, reconnectAttempt + 1, true)
              return
            }
            throw error
          }
          
          if (!response.body) throw new Error('服务端未返回事件流')
          
          const terminal = await consumeStream(response.body, async event => {
            reconnectAttempt = 0
            await onEvent(event)
          }, controller.signal)
          
          if (terminal) {
            release()
            return
          }
        } catch (error) {
          if (controller.signal.aborted) return
          onError?.(error, reconnectAttempt + 1)
        }
        
        // 指数退避重连
        const delay = Math.min(15_000, 1_000 * 2 ** reconnectAttempt++)
        await waitForRetry(delay, controller.signal)
      }
    }

    run().catch(error => {
      if (controller.signal.aborted) return
      release()
      onError?.(error, reconnectAttempt + 1, true)
    })
  }

  return { start, stop, stopAll }
}

function waitForRetry(delay, signal) {
  if (signal.aborted) return Promise.resolve()
  return new Promise(resolve => {
    const timer = setTimeout(finish, delay)
    signal.addEventListener('abort', finish, { once: true })

    function finish() {
      clearTimeout(timer)
      signal.removeEventListener('abort', finish)
      resolve()
    }
  })
}

async function consumeStream(body, onEvent, signal) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      
      for (const frame of frames) {
        const data = frame.split(/\r?\n/)
          .filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart())
          .join('\n')
        
        if (!data) continue
        const event = JSON.parse(data)
        await onEvent(event)
        
        if (event.state === 'SUCCESS' || event.state === 'FAILED') {
          return true // 终态
        }
      }
      
      if (done) return false
    }
    return false
  } finally {
    reader.releaseLock()
  }
}
```

---

#### 2.2.2 修改 API 层增加 SSE 端点

**修改文件**：`client/src/api/index.js`

```javascript
// 新增：导出 apiRequest 供 SSE 使用
export function apiRequest(path, options = {}) {
  return fetch(`${BASE_URL}${path}`, {
    ...options,
    credentials: 'include'
  })
}

// 新增：AI 分析 SSE 端点
export function subscribeAiEvents(mediaId) {
  return `/debug/ai-events?id=${mediaId}`
}

// 新增：文字提取 SSE 端点
export function subscribeTranscribeEvents(mediaId) {
  return `/debug/transcribe-events?id=${mediaId}`
}
```

---

#### 2.2.3 修改 `useMedia.js` 替换轮询为 SSE

**修改文件**：`client/src/composables/useMedia.js`

```javascript
import { ref, computed, watch } from 'vue'
import { marked } from 'marked'
import { useAuth } from './useAuth.js'
import { useNotice } from './useNotice.js'
import { useConfirm } from './useConfirm.js'
import { createTaskStreams } from './useTaskEvents.js'
import * as api from '../api/index.js'

// ---- 模块级状态 ----
const list = ref([])
const sidebar = ref({ visible: false, type: 'ai', id: null, title: '', content: '', loading: false })

// 创建 SSE 流管理器
const taskStreams = createTaskStreams()

const { currentUser } = useAuth()
const { showMsg } = useNotice()
const { confirm } = useConfirm()

// 列表联动
watch(currentUser, (user) => {
  if (user) fetchList()
  else {
    list.value = []
    taskStreams.stopAll() // 登出时关闭所有 SSE 连接
  }
})

// Markdown 渲染
const renderedMarkdown = computed(() => {
  if (!sidebar.value.content) return ''
  let cleanText = sidebar.value.content.replace(/<think>[\s\S]*?<\/think>/gi, "")
  if (cleanText.includes("</think>")) cleanText = cleanText.split("</think>").pop()
  if (!cleanText.trim()) cleanText = sidebar.value.content
  return marked.parse(cleanText)
})

async function fetchList() {
  try {
    if (currentUser.value) {
      const res = await api.getMediaList(currentUser.value.id)
      const data = await res.json()
      list.value = data
    } else {
      list.value = []
    }
  } catch (error) {
    console.error(error)
  }
}

// ... deleteItem, downloadAudio 保持不变 ...

// 文字提取（SSE 版本）
async function transcribe(id) {
  const item = list.value.find(i => i.id === id)
  const st = item?.transcriptStatus || 'NONE'

  // 1. 已完成 → 直接显示结果
  if (st === 'SUCCESS' || st === 'FAILED') {
    openSidebar('text', '全量文字提取', id)
    sidebar.value.content = st === 'FAILED' ? '❌ 提取失败，请稍后重试' : (item.transcriptText || '')
    sidebar.value.loading = false
    return
  }

  // 2. 正在处理 → 打开转圈并订阅 SSE
  if (st === 'PROCESSING') {
    openSidebar('text', '全量文字提取', id)
    sidebar.value.loading = true
    sidebar.value.content = "文字转写中..."
    startSSEStream(id, 'transcription')
    return
  }

  // 3. NONE → 提交请求
  openSidebar('text', '全量文字提取', id)
  sidebar.value.loading = true
  sidebar.value.content = "资源请求中..."
  
  try {
    const res = await api.transcribe(id)
    const data = await res.json()
    if (data.code !== 0) {
      showMsg(data.message || '提交失败', true)
      sidebar.value.content = data.message || '提交失败'
      sidebar.value.loading = false
      return
    }
    sidebar.value.content = "任务已提交，等待处理..."
    startSSEStream(id, 'transcription')
  } catch (e) {
    sidebar.value.content = "Error: " + e
    sidebar.value.loading = false
  }
}

// AI 分析（SSE 版本）
async function aiAnalyze(id) {
  const item = list.value.find(i => i.id === id)
  const st = item?.aiStatus || 'NONE'

  // 1. 已完成 → 直接显示结果
  if (st === 'SUCCESS' || st === 'FAILED') {
    openSidebar('ai', 'AI 智能总结', id)
    sidebar.value.content = st === 'FAILED' ? '❌ 分析失败，请稍后重试' : (item.aiSummary || '')
    sidebar.value.loading = false
    return
  }

  // 2. 正在处理 → 打开转圈并订阅 SSE
  if (st === 'PENDING' || st === 'PROCESSING') {
    openSidebar('ai', 'AI 智能总结', id)
    sidebar.value.loading = true
    sidebar.value.content = st === 'PENDING' ? 'AI调用中...' : 'AI分析中...'
    startSSEStream(id, 'analysis')
    return
  }

  // 3. 准备提交请求
  openSidebar('ai', 'AI 智能总结', id)
  sidebar.value.loading = true
  sidebar.value.content = "资源请求中..."

  try {
    const res = await api.aiAnalyze(id)
    const data = await res.json()

    if (data.code !== 0) {
      showMsg(data.message || '提交失败', true)
      sidebar.value.content = data.message || '提交失败'
      sidebar.value.loading = false
      return
    }

    sidebar.value.content = "任务已提交，等待AI响应..."
    startSSEStream(id, 'analysis')
  } catch (e) {
    sidebar.value.content = "Error: " + e
    sidebar.value.loading = false
  }
}

// 启动 SSE 流订阅
function startSSEStream(id, type) {
  const path = type === 'analysis' 
    ? api.subscribeAiEvents(id) 
    : api.subscribeTranscribeEvents(id)

  taskStreams.start(
    id,
    type,
    path,
    async (event) => {
      // SSE 事件回调
      console.log(`[SSE] ${type} 事件:`, event)
      
      // 刷新列表以更新状态
      await fetchList()
      const item = list.value.find(i => i.id === id)
      
      // 更新侧边栏显示
      if (sidebar.value.visible && sidebar.value.id === id) {
        const status = event.state
        
        if (status === 'SUCCESS') {
          sidebar.value.content = type === 'analysis' 
            ? (item?.aiSummary || '分析完成但内容为空') 
            : (item?.transcriptText || '提取完成但内容为空')
          sidebar.value.loading = false
          showMsg("✅ 任务完成")
        } else if (status === 'FAILED') {
          sidebar.value.content = type === 'analysis' 
            ? '❌ 分析失败，请稍后重试' 
            : '❌ 提取失败，请稍后重试'
          sidebar.value.loading = false
          showMsg("⚠️ 任务失败", true)
        } else if (status === 'PROCESSING') {
          sidebar.value.content = type === 'analysis' ? 'AI分析中...' : '文字转写中...'
          sidebar.value.loading = true
        } else if (status === 'PENDING') {
          sidebar.value.content = 'AI调用中...'
          sidebar.value.loading = true
        }
      }
    },
    (error, attempt, terminal) => {
      // SSE 错误回调
      console.error(`[SSE] ${type} 连接错误:`, error, `尝试次数: ${attempt}`)
      
      if (terminal) {
        // 终态错误（不再重连）
        if (sidebar.value.visible && sidebar.value.id === id) {
          sidebar.value.content = '连接已断开，请刷新页面重试'
          sidebar.value.loading = false
        }
        showMsg('⚠️ 实时推送连接失败', true)
      }
    }
  )
}

function openSidebar(type, title, id) {
  sidebar.value.visible = true
  sidebar.value.type = type
  sidebar.value.id = id
  sidebar.value.title = title
  sidebar.value.loading = true
  sidebar.value.content = ''
}

function closeSidebar() {
  sidebar.value.visible = false
  // 关闭侧边栏时停止对应的 SSE 连接
  if (sidebar.value.id) {
    taskStreams.stop(sidebar.value.id, sidebar.value.type)
  }
}

export function useMedia() {
  return {
    list,
    sidebar,
    renderedMarkdown,
    fetchList,
    deleteItem,
    downloadAudio,
    transcribe,
    aiAnalyze,
    openSidebar,
    closeSidebar,
  }
}
```

---

## 三、改造总结

### 3.1 核心变更点

| 层级 | 改造内容 | 文件 |
|------|----------|------|
| **后端服务层** | 新增 `TaskEventService` 统一管理 SSE 连接与事件推送 | `TaskEventService.java` |
| **后端配置层** | 新增 Redis 监听器配置支持跨实例事件广播 | `TaskEventRedisConfig.java` |
| **后端控制层** | 在 `DebugController` 增加 SSE 端点 | `DebugController.java` |
| **后端业务层** | 在状态变更处调用 `taskEventService.publish*()` | `VideoAnalysisConsumer.java`<br>`AiService.java` |
| **前端工具层** | 新增 SSE 连接管理模块 | `useTaskEvents.js` |
| **前端 API 层** | 导出 SSE 端点路径 | `api/index.js` |
| **前端业务层** | 移除轮询逻辑，改为 SSE 订阅 | `useMedia.js` |

---

### 3.2 改造优势

| 对比维度 | 轮询（改造前） | SSE（改造后） |
|----------|---------------|---------------|
| **延迟** | 最高 3 秒 | 实时推送（<100ms） |
| **资源消耗** | 高（每 3s 刷新整个列表） | 低（仅状态变更时推送） |
| **用户体验** | 转圈等待，无进度感知 | 实时阶段变化（PENDING → PROCESSING → SUCCESS） |
| **服务器压力** | 大量无效轮询请求 | 长连接维护，按需推送 |
| **分布式支持** | 无 | 通过 Redis Pub/Sub 跨实例广播 |
| **错误处理** | 10 分钟强制超时 | 指数退避重连 + 终态识别 |

---

### 3.3 注意事项

1. **Redis 依赖**：SSE 的跨实例广播依赖 Redis Pub/Sub，确保 Redis 可用性。
2. **连接数限制**：SSE 是长连接，建议配置 Nginx 增加 `proxy_read_timeout` 至 30 分钟。
3. **浏览器兼容性**：SSE 不支持 IE，但现代浏览器（Chrome/Firefox/Safari）均支持。
4. **并发控制**：单个用户可能同时订阅多个任务，连接池设计已支持。
5. **回退策略**：Redis 不可用时自动降级为本地推送（单实例场景仍可用）。

---

### 3.4 测试清单

- [ ] 单实例场景：提交任务 → SSE 实时推送 → 侧边栏更新
- [ ] 多实例场景：实例 A 提交 → 实例 B 的 SSE 订阅者也能收到事件
- [ ] 重连测试：手动断开 SSE 连接 → 自动指数退避重连
- [ ] 终态测试：任务完成后 SSE 自动关闭，不再重连
- [ ] 并发测试：同时提交多个任务，各自独立推送
- [ ] Redis 故障：Redis 宕机后降级到本地推送（单实例仍可用）

---

## 四、迁移路径

### 阶段 1：灰度测试（保留轮询）
- 新增 SSE 端点，但不删除轮询逻辑
- 前端同时启用 SSE + 轮询，SSE 优先，轮询兜底
- 灰度 20% 用户使用 SSE

### 阶段 2：全量切换
- SSE 稳定后，前端移除轮询代码
- 后端保留状态查询接口（用于页面刷新时获取初始状态）

### 阶段 3：清理
- 移除轮询相关的兜底逻辑（10 轮 NONE 检测、10 分钟超时）
- 删除 `pollingTimers` 相关代码
