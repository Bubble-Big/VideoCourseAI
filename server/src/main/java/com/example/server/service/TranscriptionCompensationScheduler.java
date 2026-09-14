package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.common.AiStatus;
import com.example.server.entity.MediaFile;
import com.example.server.exception.AiAnalysisException;
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
 * 文字提取补偿调度器：继承抽象基类，定制化实现文字提取的补偿逻辑。
 */
@Component
public class TranscriptionCompensationScheduler extends AbstractCompensationScheduler {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionCompensationScheduler.class);

    @Value("${transcription.compensation.threshold-minutes:15}")
    private long thresholdMinutes;

    @Value("${transcription.compensation.max-attempts:3}")
    private int maxAttempts;

    private final AiService aiService;

    public TranscriptionCompensationScheduler(MediaFileMapper mediaFileMapper,
                                             AiService aiService,
                                             TaskEventService taskEventService,
                                             RedissonClient redissonClient) {
        super(mediaFileMapper, redissonClient, taskEventService);
        this.aiService = aiService;
    }

    @Override
    protected String getLockKey() {
        return "lock:scheduler:transcription-compensation";
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
        return "文字提取补偿调度器";
    }

    @Override
    protected List<MediaFile> scanStalledTasks(LocalDateTime threshold, int limit) {
        return mediaFileMapper.selectStalledTranscription(threshold, limit);
    }

    @Override
    protected CompletableFuture<?> triggerRetry(Long mediaId) {
        aiService.asyncTranscribe(mediaId, false);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    protected String getStatus(MediaFile file) {
        return file.getTranscriptStatus();
    }

    @Override
    protected Integer getCompensationAttempts(MediaFile file) {
        return file.getTranscriptCompensationAttempts();
    }

    @Override
    protected Integer getRetryCount(MediaFile file) {
        return file.getTranscriptRetryCount();
    }

    @Override
    protected LocalDateTime getProcessAt(MediaFile file) {
        return file.getTranscriptProcessAt();
    }

    @Override
    protected void setStatus(LambdaUpdateWrapper<MediaFile> wrapper, String status) {
        wrapper.set(MediaFile::getTranscriptStatus, status)
               .set(MediaFile::getTranscriptText, null);
    }

    @Override
    protected void setCompensationAttempts(LambdaUpdateWrapper<MediaFile> wrapper, int attempts) {
        wrapper.set(MediaFile::getTranscriptCompensationAttempts, attempts);
    }

    @Override
    protected void setProcessAt(LambdaUpdateWrapper<MediaFile> wrapper, LocalDateTime time) {
        wrapper.set(MediaFile::getTranscriptProcessAt, time);
    }

    @Override
    protected void refreshProcessAtField(LambdaUpdateWrapper<MediaFile> wrapper, LocalDateTime time) {
        wrapper.set(MediaFile::getTranscriptProcessAt, time);
    }

    @Override
    protected void recordFailure(Long mediaId, Exception ex, int attempts) {
        log.error("文字提取重试耗尽, mediaId={}, attempts={}, err={}",
            mediaId, attempts, ex.getMessage());
    }

    @Override
    protected void publishFailure(Long mediaId, String errorMsg) {
        taskEventService.publishTranscription(mediaId, AiStatus.FAILED.name(), null, errorMsg);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void schedule() {
        compensate();
    }
}
