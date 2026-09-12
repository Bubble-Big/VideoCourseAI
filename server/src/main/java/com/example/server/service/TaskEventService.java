package com.example.server.service;

import com.example.server.dto.TaskEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 任务事件推送服务
 *
 * 核心职责：
 * 1. 管理 SSE 连接池（按 "type:mediaId" 分组）
 * 2. 通过 Redis Pub/Sub 实现跨实例广播
 * 3. Redis 不可用时自动降级到本地推送；定期探活后自动恢复
 * 4. 终态事件（SUCCESS/FAILED）自动关闭连接
 * 5. 定期心跳防止反向代理超时断开长连接
 */
@Service
public class TaskEventService implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(TaskEventService.class);

    /** Redis Pub/Sub 频道名 */
    private static final String REDIS_CHANNEL = "videocourse:task-events";

    /** SSE 连接超时时间（30 分钟） */
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    /** 连接池：key = "type:mediaId"，value = 订阅该任务的所有 SSE 连接 */
    private final Map<String, List<SseEmitter>> emitterPool = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** Redis 是否可用（启动检查 + 运行时降级标记） */
    private volatile boolean redisAvailable = true;

    public TaskEventService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动检查：验证 Redis Pub/Sub 连接
     */
    @PostConstruct
    public void checkRedisConnection() {
        try {
            redisTemplate.convertAndSend(REDIS_CHANNEL, "{\"test\":\"ping\"}");
            redisAvailable = true;
            log.info("✅ Redis Pub/Sub 配置正常，SSE 支持跨实例广播");
        } catch (Exception e) {
            redisAvailable = false;
            log.warn("⚠️ Redis Pub/Sub 不可用，SSE 降级为单实例模式", e);
        }
    }

    /**
     * Redis 定期探活（每 30 秒）：降级后自动恢复
     */
    @Scheduled(fixedDelay = 30_000)
    public void probeRedis() {
        if (redisAvailable) return;
        try {
            redisTemplate.convertAndSend(REDIS_CHANNEL, "{\"test\":\"ping\"}");
            redisAvailable = true;
            log.info("✅ Redis Pub/Sub 已恢复，SSE 恢复跨实例广播模式");
        } catch (Exception ignored) {
            // 仍不可用，保持降级状态，下次再试
        }
    }

    /**
     * SSE 心跳（每 25 秒）：防止反向代理超时断开长连接
     */
    @Scheduled(fixedDelay = 25_000)
    public void sendHeartbeat() {
        if (emitterPool.isEmpty()) return;
        emitterPool.forEach((key, emitters) -> {
            emitters.forEach(emitter -> {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (IOException e) {
                    removeEmitter(key, emitter);
                }
            });
        });
    }

    /**
     * 订阅任务事件流（前端调用）
     *
     * @param mediaId      媒体文件 ID
     * @param type         任务类型：ai / transcribe
     * @param initialEvent 初始状态事件（查询数据库获取当前状态后传入）
     * @return SSE Emitter
     */
    public SseEmitter subscribe(Long mediaId, String type, TaskEvent initialEvent) {
        String key = buildKey(type, mediaId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // 先注册到连接池，再推送初始状态，避免注册前事件到达被遗漏的竞态窗口
        emitterPool.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);

        // 设置连接生命周期回调
        emitter.onCompletion(() -> removeEmitter(key, emitter));
        emitter.onTimeout(() -> {
            log.debug("SSE 连接超时：{}", key);
            removeEmitter(key, emitter);
        });
        emitter.onError(ex -> {
            log.warn("SSE 连接异常：{}", key, ex);
            removeEmitter(key, emitter);
        });

        // 推送初始状态
        try {
            sendEvent(emitter, initialEvent);
            log.debug("SSE 订阅建立：{} 初始状态={}", key, initialEvent.state());
        } catch (IOException e) {
            log.warn("SSE 初始推送失败：{}", key, e);
            removeEmitter(key, emitter);
            return emitter;
        }

        // 初始状态已是终态（任务已完成）：立即关闭，不占用连接池
        if (initialEvent.isTerminal()) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
            emitterPool.remove(key);
            log.debug("SSE 初始即终态，立即关闭：{} state={}", key, initialEvent.state());
        }

        return emitter;
    }

    /**
     * 发布 AI 分析事件
     */
    public void publishAnalysis(Long mediaId, String state, String aiSummary, String error) {
        TaskEvent event = TaskEvent.analysis(mediaId, state, aiSummary, error);
        publish("ai", mediaId, event);
    }

    /**
     * 发布文字提取事件
     */
    public void publishTranscription(Long mediaId, String state, String transcriptText, String error) {
        TaskEvent event = TaskEvent.transcription(mediaId, state, transcriptText, error);
        publish("transcribe", mediaId, event);
    }

    /**
     * 发布事件（核心逻辑）
     *
     * 1. 优先通过 Redis Pub/Sub 广播（跨实例）
     * 2. Redis 故障时降级到本地推送（单实例）
     */
    private void publish(String type, Long mediaId, TaskEvent event) {
        String key = buildKey(type, mediaId);

        try {
            if (redisAvailable) {
                String message = objectMapper.writeValueAsString(Map.of(
                    "key", key,
                    "event", event
                ));
                redisTemplate.convertAndSend(REDIS_CHANNEL, message);
                log.debug("SSE 事件已广播（Redis）：{} state={}", key, event.state());
            } else {
                pushToLocalEmitters(key, event);
                log.debug("SSE 事件本地推送：{} state={}", key, event.state());
            }
        } catch (Exception e) {
            log.error("SSE 事件发布失败：{}", key, e);
            // Redis 异常时标记降级并尝试本地推送
            redisAvailable = false;
            pushToLocalEmitters(key, event);
        }
    }

    /**
     * Redis Pub/Sub 消息监听（接收来自其他实例的广播）
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(body, Map.class);

            String key = (String) data.get("key");
            // 启动 ping 或其他无 key 的测试消息，忽略
            if (key == null) return;

            @SuppressWarnings("unchecked")
            Map<String, Object> eventMap = (Map<String, Object>) data.get("event");
            if (eventMap == null) return;

            TaskEvent event = objectMapper.convertValue(eventMap, TaskEvent.class);
            pushToLocalEmitters(key, event);

        } catch (Exception e) {
            log.error("SSE Redis 消息解析失败", e);
        }
    }

    /**
     * 推送事件到本地连接池
     */
    private void pushToLocalEmitters(String key, TaskEvent event) {
        List<SseEmitter> emitters = emitterPool.get(key);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                sendEvent(emitter, event);
            } catch (IOException e) {
                log.warn("SSE 推送失败，移除连接：{}", key, e);
                removeEmitter(key, emitter);
            }
        }

        // 终态事件：批量关闭并清理连接池（onCompletion 回调会尝试再次 remove，CopyOnWriteArrayList 保证幂等）
        if (event.isTerminal()) {
            List<SseEmitter> toClose = emitterPool.remove(key);
            if (toClose != null) {
                for (SseEmitter emitter : toClose) {
                    try {
                        emitter.complete();
                    } catch (Exception e) {
                        log.debug("SSE 关闭连接异常：{}", key, e);
                    }
                }
            }
            log.debug("SSE 连接已关闭（终态）：{} state={}", key, event.state());
        }
    }

    /**
     * 发送 SSE 事件
     */
    private void sendEvent(SseEmitter emitter, TaskEvent event) throws IOException {
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event()
                .data(json)
                .id(String.valueOf(event.timestamp())));
        } catch (JsonProcessingException e) {
            log.error("TaskEvent 序列化失败", e);
            throw new IOException(e);
        }
    }

    /**
     * 移除连接
     */
    private void removeEmitter(String key, SseEmitter emitter) {
        List<SseEmitter> emitters = emitterPool.get(key);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emitterPool.remove(key);
            }
        }
    }

    /**
     * 构建连接池 key: "type:mediaId"
     */
    private String buildKey(String type, Long mediaId) {
        return type + ":" + mediaId;
    }

    /**
     * 获取当前连接数（用于监控）
     */
    public int getActiveConnectionCount() {
        return emitterPool.values().stream()
            .mapToInt(List::size)
            .sum();
    }
}

    /** Redis Pub/Sub 频道名 */
    private static final String REDIS_CHANNEL = "videocourse:task-events";

    /** SSE 连接超时时间（30 分钟） */
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    /** 连接池：key = "type:mediaId"，value = 订阅该任务的所有 SSE 连接 */
    private final Map<String, List<SseEmitter>> emitterPool = new ConcurrentHashMap<>();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** Redis 是否可用（启动检查 + 运行时降级标记） */
    private volatile boolean redisAvailable = true;

    public TaskEventService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动检查：验证 Redis Pub/Sub 连接
     */
    @PostConstruct
    public void checkRedisConnection() {
        try {
            redisTemplate.convertAndSend(REDIS_CHANNEL, "{\"test\":\"ping\"}");
            redisAvailable = true;
            log.info("✅ Redis Pub/Sub 配置正常，SSE 支持跨实例广播");
        } catch (Exception e) {
            redisAvailable = false;
            log.warn("⚠️ Redis Pub/Sub 不可用，SSE 降级为单实例模式", e);
        }
    }

    /**
     * 订阅任务事件流（前端调用）
     *
     * @param mediaId 媒体文件 ID
     * @param type 任务类型：ai / transcribe
     * @param initialEvent 初始状态事件（查询数据库获取当前状态后传入）
     * @return SSE Emitter
     */
    public SseEmitter subscribe(Long mediaId, String type, TaskEvent initialEvent) {
        String key = buildKey(type, mediaId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // 立即推送初始状态（携带完整数据）
        try {
            sendEvent(emitter, initialEvent);
            log.debug("SSE 订阅建立：{} 初始状态={}", key, initialEvent.state());
        } catch (IOException e) {
            log.warn("SSE 初始推送失败：{}", key, e);
            return emitter;
        }

        // 注册到连接池
        emitterPool.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(emitter);

        // 设置连接生命周期回调
        emitter.onCompletion(() -> removeEmitter(key, emitter));
        emitter.onTimeout(() -> {
            log.debug("SSE 连接超时：{}", key);
            removeEmitter(key, emitter);
        });
        emitter.onError(ex -> {
            log.warn("SSE 连接异常：{}", key, ex);
            removeEmitter(key, emitter);
        });

        return emitter;
    }

    /**
     * 发布 AI 分析事件
     */
    public void publishAnalysis(Long mediaId, String state, String aiSummary, String error) {
        TaskEvent event = TaskEvent.analysis(mediaId, state, aiSummary, error);
        publish("ai", mediaId, event);
    }

    /**
     * 发布文字提取事件
     */
    public void publishTranscription(Long mediaId, String state, String transcriptText, String error) {
        TaskEvent event = TaskEvent.transcription(mediaId, state, transcriptText, error);
        publish("transcribe", mediaId, event);
    }

    /**
     * 发布事件（核心逻辑）
     *
     * 1. 优先通过 Redis Pub/Sub 广播（跨实例）
     * 2. Redis 故障时降级到本地推送（单实例）
     */
    private void publish(String type, Long mediaId, TaskEvent event) {
        String key = buildKey(type, mediaId);

        try {
            if (redisAvailable) {
                // Redis 可用：通过 Pub/Sub 广播
                String message = objectMapper.writeValueAsString(Map.of(
                    "key", key,
                    "event", event
                ));
                redisTemplate.convertAndSend(REDIS_CHANNEL, message);
                log.debug("SSE 事件已广播（Redis）：{} state={}", key, event.state());
            } else {
                // Redis 不可用：本地推送
                pushToLocalEmitters(key, event);
                log.debug("SSE 事件本地推送：{} state={}", key, event.state());
            }
        } catch (Exception e) {
            log.error("SSE 事件发布失败：{}", key, e);
            // Redis 异常时标记降级并尝试本地推送
            redisAvailable = false;
            pushToLocalEmitters(key, event);
        }
    }

    /**
     * Redis Pub/Sub 消息监听（接收来自其他实例的广播）
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.readValue(body, Map.class);

            String key = (String) data.get("key");
            @SuppressWarnings("unchecked")
            Map<String, Object> eventMap = (Map<String, Object>) data.get("event");

            TaskEvent event = objectMapper.convertValue(eventMap, TaskEvent.class);
            pushToLocalEmitters(key, event);

        } catch (Exception e) {
            log.error("SSE Redis 消息解析失败", e);
        }
    }

    /**
     * 推送事件到本地连接池
     */
    private void pushToLocalEmitters(String key, TaskEvent event) {
        List<SseEmitter> emitters = emitterPool.get(key);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        // 推送给所有订阅者
        for (SseEmitter emitter : emitters) {
            try {
                sendEvent(emitter, event);
            } catch (IOException e) {
                log.warn("SSE 推送失败，移除连接：{}", key, e);
                removeEmitter(key, emitter);
            }
        }

        // 终态事件：自动关闭所有连接
        if (event.isTerminal()) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.debug("SSE 关闭连接异常：{}", key, e);
                }
            }
            emitterPool.remove(key);
            log.debug("SSE 连接已关闭（终态）：{} state={}", key, event.state());
        }
    }

    /**
     * 发送 SSE 事件
     */
    private void sendEvent(SseEmitter emitter, TaskEvent event) throws IOException {
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event()
                .data(json)
                .id(String.valueOf(event.timestamp())));
        } catch (JsonProcessingException e) {
            log.error("TaskEvent 序列化失败", e);
            throw new IOException(e);
        }
    }

    /**
     * 移除连接
     */
    private void removeEmitter(String key, SseEmitter emitter) {
        List<SseEmitter> emitters = emitterPool.get(key);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                emitterPool.remove(key);
            }
        }
    }

    /**
     * 构建连接池 key: "type:mediaId"
     */
    private String buildKey(String type, Long mediaId) {
        return type + ":" + mediaId;
    }

    /**
     * 获取当前连接数（用于监控）
     */
    public int getActiveConnectionCount() {
        return emitterPool.values().stream()
            .mapToInt(List::size)
            .sum();
    }
}
