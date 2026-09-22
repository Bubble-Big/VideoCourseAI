package com.example.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync //开启异步注解支持
public class ThreadPoolConfig {

    @Bean("aiTaskExecutor") //给线程池起个名
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);

        // 3. 队列容量：如果 8 个人都忙，新的任务在门口排队，最多排 100 个
        executor.setQueueCapacity(100);

        // 4. 线程名称前缀：方便在日志里看是谁干的活
        executor.setThreadNamePrefix("AI-Thread-");

        // 5. 拒绝策略：队列排满（100）后新任务直接抛异常，由调用方捕获处理。
        // 不用 CallerRunsPolicy：它会把任务回退到调用线程（MQ 监听线程）同步执行，重新阻塞监听线程。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        executor.initialize();
        return executor;
    }
}