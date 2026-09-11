package com.example.server.config;

import com.example.server.service.TaskEventService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis Pub/Sub 监听器配置
 *
 * 用于订阅 SSE 事件广播频道，实现跨实例消息分发
 */
@Configuration
public class TaskEventRedisConfig {

    private static final String REDIS_CHANNEL = "videocourse:task-events";

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            TaskEventService taskEventService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(taskEventService, new ChannelTopic(REDIS_CHANNEL));
        return container;
    }
}
