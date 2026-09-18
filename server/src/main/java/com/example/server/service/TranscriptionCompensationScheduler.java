package com.example.server.service;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.common.AiStatus;
import com.example.server.entity.MediaTranscription;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.MediaTranscriptionMapper;
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
public class TranscriptionCompensationScheduler extends AbstractCompensationScheduler<MediaTranscription> {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionCompensationScheduler.class);

    @Value("${transcription.compensation.threshold-minutes:15}")
    private long thresholdMinutes;

    @Value("${transcription.compensation.max-attempts:3}")
    private int maxAttempts;

    private final AiService aiService;
    private final MediaTranscriptionMapper transcriptionMapper;

    public TranscriptionCompensationScheduler(MediaFileMapper mediaFileMapper,
                                             AiService aiService,
                                             TaskEventService taskEventService,
                                             RedissonClient redissonClient,
                                             MediaTranscriptionMapper transcriptionMapper) {
        super(mediaFileMapper, redissonClient, taskEventService);
        this.aiService = aiService;
        this.transcriptionMapper = transcriptionMapper;
    }

    @Override
    protected BaseMapper<MediaTranscription> getChildTableMapper() {
        return transcriptionMapper;
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
    protected List<MediaTranscription> scanStalledTasks(LocalDateTime threshold, int limit) {
        return transcriptionMapper.selectStalledTranscription(threshold, limit);
    }

    @Override
    protected Long getMediaId(MediaTranscription entity) {
        return entity.getMediaId();
    }

    @Override
    protected String getStatus(MediaTranscription entity) {
        return entity.getStatus();
    }

    @Override
    protected Integer getCompensationAttempts(MediaTranscription entity) {
        return entity.getCompensationAttempts();
    }

    @Override
    protected Integer getRetryCount(MediaTranscription entity) {
        return entity.getRetryCount();
    }

    @Override
    protected LocalDateTime getProcessAt(MediaTranscription entity) {
        return entity.getProcessAt();
    }

    @Override
    protected Integer getVersion(MediaTranscription entity) {
        return entity.getVersion();
    }

    @Override
    protected CompletableFuture<?> triggerRetry(Long mediaId) {
        aiService.asyncTranscribe(mediaId, false);
        return CompletableFuture.completedFuture(null);
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
