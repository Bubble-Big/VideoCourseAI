package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.common.AiStatus;
import com.example.server.entity.MediaFile;
import com.example.server.entity.MediaTranscription;
import com.example.server.exception.AiAnalysisException;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.MediaTranscriptionMapper;
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
        // 先查子表，再关联主表
        List<MediaTranscription> stalledList = transcriptionMapper.selectStalledTranscription(threshold, limit);

        if (stalledList.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量查询关联的 MediaFile（避免 N+1）
        List<Long> mediaIds = stalledList.stream()
            .map(MediaTranscription::getMediaId)
            .collect(Collectors.toList());
        return mediaFileMapper.selectBatchIds(mediaIds);
    }

    @Override
    protected CompletableFuture<?> triggerRetry(Long mediaId) {
        aiService.asyncTranscribe(mediaId, false);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    protected String getStatus(MediaFile file) {
        MediaTranscription transcription = transcriptionMapper.selectOne(
            new LambdaQueryWrapper<MediaTranscription>().eq(MediaTranscription::getMediaId, file.getId())
        );
        return transcription != null ? transcription.getStatus() : AiStatus.NONE.name();
    }

    @Override
    protected Integer getCompensationAttempts(MediaFile file) {
        MediaTranscription transcription = transcriptionMapper.selectOne(
            new LambdaQueryWrapper<MediaTranscription>().eq(MediaTranscription::getMediaId, file.getId())
        );
        return transcription != null ? transcription.getCompensationAttempts() : 0;
    }

    @Override
    protected Integer getRetryCount(MediaFile file) {
        MediaTranscription transcription = transcriptionMapper.selectOne(
            new LambdaQueryWrapper<MediaTranscription>().eq(MediaTranscription::getMediaId, file.getId())
        );
        return transcription != null ? transcription.getRetryCount() : 0;
    }

    @Override
    protected LocalDateTime getProcessAt(MediaFile file) {
        MediaTranscription transcription = transcriptionMapper.selectOne(
            new LambdaQueryWrapper<MediaTranscription>().eq(MediaTranscription::getMediaId, file.getId())
        );
        return transcription != null ? transcription.getProcessAt() : null;
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
