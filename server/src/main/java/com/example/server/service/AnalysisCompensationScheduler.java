package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.common.AiStatus;
import com.example.server.entity.MediaAiAnalysis;
import com.example.server.entity.MediaFile;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * AI 分析补偿调度器：继承抽象基类，定制化实现 AI 分析的补偿逻辑。
 */
@Component
public class AnalysisCompensationScheduler extends AbstractCompensationScheduler {

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
    protected List<MediaFile> scanStalledTasks(LocalDateTime threshold, int limit) {
        // 先查子表，再关联主表
        List<MediaAiAnalysis> stalledList = aiAnalysisMapper.selectStalledAnalysis(threshold, limit);

        if (stalledList.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量查询关联的 MediaFile（避免 N+1）
        List<Long> mediaIds = stalledList.stream()
            .map(MediaAiAnalysis::getMediaId)
            .collect(Collectors.toList());
        return mediaFileMapper.selectBatchIds(mediaIds);
    }

    @Override
    protected CompletableFuture<?> triggerRetry(Long mediaId) {
        return aiService.asyncAnalyze(mediaId, false);
    }

    @Override
    protected String getStatus(MediaFile file) {
        MediaAiAnalysis analysis = aiAnalysisMapper.selectOne(
            new LambdaQueryWrapper<MediaAiAnalysis>().eq(MediaAiAnalysis::getMediaId, file.getId())
        );
        return analysis != null ? analysis.getStatus() : AiStatus.NONE.name();
    }

    @Override
    protected Integer getCompensationAttempts(MediaFile file) {
        MediaAiAnalysis analysis = aiAnalysisMapper.selectOne(
            new LambdaQueryWrapper<MediaAiAnalysis>().eq(MediaAiAnalysis::getMediaId, file.getId())
        );
        return analysis != null ? analysis.getCompensationAttempts() : 0;
    }

    @Override
    protected Integer getRetryCount(MediaFile file) {
        MediaAiAnalysis analysis = aiAnalysisMapper.selectOne(
            new LambdaQueryWrapper<MediaAiAnalysis>().eq(MediaAiAnalysis::getMediaId, file.getId())
        );
        return analysis != null ? analysis.getRetryCount() : 0;
    }

    @Override
    protected LocalDateTime getProcessAt(MediaFile file) {
        MediaAiAnalysis analysis = aiAnalysisMapper.selectOne(
            new LambdaQueryWrapper<MediaAiAnalysis>().eq(MediaAiAnalysis::getMediaId, file.getId())
        );
        return analysis != null ? analysis.getProcessAt() : null;
    }

    @Override
    protected void setStatus(LambdaUpdateWrapper<MediaFile> wrapper, String status) {
        // 这个方法已废弃，改为直接操作子表
        // 保留空实现以兼容抽象基类
    }

    @Override
    protected void setCompensationAttempts(LambdaUpdateWrapper<MediaFile> wrapper, int attempts) {
        // 这个方法已废弃，改为直接操作子表
        // 保留空实现以兼容抽象基类
    }

    @Override
    protected void setProcessAt(LambdaUpdateWrapper<MediaFile> wrapper, LocalDateTime time) {
        // 这个方法已废弃，改为直接操作子表
        // 保留空实现以兼容抽象基类
    }

    @Override
    protected void refreshProcessAtField(LambdaUpdateWrapper<MediaFile> wrapper, LocalDateTime time) {
        // 这个方法已废弃，改为直接操作子表
        // 保留空实现以兼容抽象基类
    }

    @Override
    protected void recordFailure(Long mediaId, Exception ex, int attempts) {
        failedTaskService.record(mediaId, (AiAnalysisException) ex, attempts);
    }

    @Override
    protected void publishFailure(Long mediaId, String errorMsg) {
        taskEventService.publishAnalysis(mediaId, AiStatus.FAILED.name(), null, errorMsg);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void schedule() {
        compensate();
    }
}
