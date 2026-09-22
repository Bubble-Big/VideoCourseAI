package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.MediaAiAnalysis;
import com.example.server.exception.AiAnalysisException;
import com.example.server.mapper.MediaAiAnalysisMapper;
import com.example.server.mapper.MediaFileMapper;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * AI 分析补偿调度器：继承抽象基类，定制化实现 AI 分析的补偿逻辑。
 */
@Component
public class AnalysisCompensationScheduler extends AbstractCompensationScheduler<MediaAiAnalysis> {

    private static final Logger log = LoggerFactory.getLogger(AnalysisCompensationScheduler.class);

    @Value("${ai.compensation.threshold-minutes:20}")
    private long thresholdMinutes;

    @Value("${ai.compensation.max-attempts:3}")
    private int maxAttempts;

    private final AiService aiService;
    private final FailedAnalysisTaskService failedTaskService;
    private final MediaAiAnalysisMapper aiAnalysisMapper;

    public AnalysisCompensationScheduler(MediaFileMapper mediaFileMapper,
                                        AiService aiService,
                                        FailedAnalysisTaskService failedTaskService,
                                        TaskEventService taskEventService,
                                        RedissonClient redissonClient,
                                        MediaAiAnalysisMapper aiAnalysisMapper) {
        super(mediaFileMapper, redissonClient, taskEventService);
        this.aiService = aiService;
        this.failedTaskService = failedTaskService;
        this.aiAnalysisMapper = aiAnalysisMapper;
    }

    @Override
    protected BaseMapper<MediaAiAnalysis> getChildTableMapper() {
        return aiAnalysisMapper;
    }

    @Override
    protected String getLockKey() {
        return "lock:scheduler:analysis-compensation";
    }

    @Override
    protected long getThresholdMinutes() {
        return thresholdMinutes;
    }

    @Override
    protected int getMaxAttempts() {
        return maxAttempts;
    }

    @Override
    protected String getSchedulerName() {
        return "AI分析补偿调度器";
    }

    @Override
    protected List<MediaAiAnalysis> scanStalledTasks(LocalDateTime threshold, int limit) {
        return aiAnalysisMapper.selectStalledAnalysis(threshold, limit);
    }

    @Override
    protected Long getMediaId(MediaAiAnalysis entity) {
        return entity.getMediaId();
    }

    @Override
    protected String getStatus(MediaAiAnalysis entity) {
        return entity.getStatus();
    }

    @Override
    protected Integer getCompensationAttempts(MediaAiAnalysis entity) {
        return entity.getCompensationAttempts();
    }

    @Override
    protected Integer getRetryCount(MediaAiAnalysis entity) {
        return entity.getRetryCount();
    }

    @Override
    protected LocalDateTime getProcessAt(MediaAiAnalysis entity) {
        return entity.getProcessAt();
    }

    @Override
    protected Integer getVersion(MediaAiAnalysis entity) {
        return entity.getVersion();
    }

    @Override
    protected CompletableFuture<?> triggerRetry(Long mediaId) {
        return aiService.asyncAnalyze(mediaId, false);
    }

    @Override
    protected void recordFailure(Long mediaId, Exception ex, int attempts) {
        failedTaskService.record(mediaId, (AiAnalysisException) ex, attempts);
    }

    @Override
    protected void publishFailure(Long mediaId, String errorMsg) {
        taskEventService.publishAnalysis(mediaId, com.example.server.common.AiStatus.FAILED.name(), null, errorMsg);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void schedule() {
        compensate();
    }
}
