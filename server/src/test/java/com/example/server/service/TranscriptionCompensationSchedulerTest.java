package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.common.AiStatus;
import com.example.server.entity.MediaTranscription;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.MediaTranscriptionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TranscriptionCompensationScheduler 单元测试（子表适配版）
 * <p>
 * 覆盖文字转写补偿调度器的核心场景：
 * - 场景 1：扫描 media_transcription 子表卡死记录
 * - 场景 2：乐观锁版本冲突检测
 * - 场景 3：达到最大重试次数标记 FAILED
 * - 场景 4：单条记录异常隔离
 * - 场景 5：分布式锁互斥
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class TranscriptionCompensationSchedulerTest {

    @Mock
    private MediaFileMapper mediaFileMapper;

    @Mock
    private MediaTranscriptionMapper transcriptionMapper;

    @Mock
    private AiService aiService;

    @Mock
    private TaskEventService taskEventService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock schedulerLock;

    private TranscriptionCompensationScheduler scheduler;

    private static final Long MEDIA_ID = 200L;
    private static final LocalDateTime STALE_TIME = LocalDateTime.now().minusMinutes(25);

    @BeforeEach
    void setUp() {
        scheduler = new TranscriptionCompensationScheduler(
            mediaFileMapper,
            aiService,
            taskEventService,
            redissonClient,
            transcriptionMapper
        );
        ReflectionTestUtils.setField(scheduler, "thresholdMinutes", 15L);
        ReflectionTestUtils.setField(scheduler, "maxAttempts", 3);
    }

    // ==================== 场景 5：分布式锁互斥 ====================

    @Test
    void testCompensate_DistributedLock_Success() throws InterruptedException {
        when(redissonClient.getLock("lock:scheduler:transcription-compensation")).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(0L, 50L, TimeUnit.SECONDS)).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(transcriptionMapper.selectStalledTranscription(any(LocalDateTime.class), anyInt()))
            .thenReturn(Collections.emptyList());

        scheduler.compensate();

        verify(schedulerLock).tryLock(0L, 50L, TimeUnit.SECONDS);
        verify(transcriptionMapper).selectStalledTranscription(any(LocalDateTime.class), eq(100));
        verify(schedulerLock).unlock();
    }

    @Test
    void testCompensate_DistributedLock_CannotAcquire() throws InterruptedException {
        when(redissonClient.getLock("lock:scheduler:transcription-compensation")).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        scheduler.compensate();

        verify(transcriptionMapper, never()).selectStalledTranscription(any(), anyInt());
        verify(schedulerLock, never()).unlock();
    }

    // ==================== 场景 1：扫描子表卡死记录 ====================

    @Test
    void testCompensate_ScanStalledTasks_FromChildTable() throws InterruptedException {
        MediaTranscription stalled = createStalledTranscription(MEDIA_ID, 0, 0);

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(transcriptionMapper.selectStalledTranscription(any(LocalDateTime.class), eq(100)))
            .thenReturn(List.of(stalled));
        when(transcriptionMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        scheduler.compensate();

        verify(transcriptionMapper).selectStalledTranscription(any(LocalDateTime.class), eq(100));
        verify(transcriptionMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(aiService).asyncTranscribe(MEDIA_ID, false);
    }

    // ==================== 场景 2：乐观锁版本冲突 ====================

    @Test
    void testCompensateOne_VersionConflict_SkipUpdate() throws InterruptedException {
        MediaTranscription stalled = createStalledTranscription(MEDIA_ID, 0, 3);

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(transcriptionMapper.selectStalledTranscription(any(), anyInt())).thenReturn(List.of(stalled));
        when(transcriptionMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0); // 版本冲突

        scheduler.compensate();

        verify(transcriptionMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(aiService, never()).asyncTranscribe(anyLong(), anyBoolean());
    }

    // ==================== 场景 3：达到最大重试次数 ====================

    @Test
    void testIncrementAttempts_ReachMaxAttempts_MarkFailed() throws InterruptedException {
        MediaTranscription stalled = createStalledTranscription(MEDIA_ID, 2, 1);
        MediaTranscription latest = createStalledTranscription(MEDIA_ID, 2, 1);
        latest.setStatus(AiStatus.PROCESSING.name());

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(transcriptionMapper.selectStalledTranscription(any(), anyInt())).thenReturn(List.of(stalled));
        when(transcriptionMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(transcriptionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(latest);

        scheduler.compensate();
        Thread.sleep(200);

        // 验证达到 maxAttempts=3 时调用失败处理
        verify(taskEventService).publishTranscription(eq(MEDIA_ID), eq(AiStatus.FAILED.name()), isNull(), anyString());
    }

    // ==================== 场景 4：单条记录异常隔离 ====================

    @Test
    void testCompensate_SingleRecordException_NotInterruptLoop() throws InterruptedException {
        MediaTranscription trans1 = createStalledTranscription(1L, 0, 0);
        MediaTranscription trans2 = createStalledTranscription(2L, 0, 0);
        MediaTranscription trans3 = createStalledTranscription(3L, 0, 0);

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(transcriptionMapper.selectStalledTranscription(any(), anyInt()))
            .thenReturn(Arrays.asList(trans1, trans2, trans3));

        // 使用 answer 来区分不同的 mediaId
        when(transcriptionMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenAnswer(invocation -> {
            LambdaUpdateWrapper<?> wrapper = invocation.getArgument(1);
            String sql = wrapper.getSqlSegment();
            if (sql != null && sql.contains("media_id = 2")) {
                throw new RuntimeException("模拟数据库异常");
            }
            return 1;
        });

        scheduler.compensate();

        // 验证即使 trans2 异常，trans1 和 trans3 仍然被处理
        verify(aiService).asyncTranscribe(1L, false);
        verify(aiService).asyncTranscribe(3L, false);
        verify(schedulerLock).unlock();
    }

    @Test
    void testCompensate_EmptyList_NoProcessing() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(transcriptionMapper.selectStalledTranscription(any(), anyInt())).thenReturn(Collections.emptyList());

        scheduler.compensate();

        verify(aiService, never()).asyncTranscribe(anyLong(), anyBoolean());
        verify(schedulerLock).unlock();
    }

    // ==================== retryCount 冲突检测 ====================

    @Test
    void testIncrementAttempts_RetryCountChanged_SkipIncrement() throws InterruptedException {
        MediaTranscription stalled = createStalledTranscription(MEDIA_ID, 0, 1);
        stalled.setRetryCount(0); // 快照时 retryCount=0

        MediaTranscription latest = createStalledTranscription(MEDIA_ID, 0, 1);
        latest.setStatus(AiStatus.PROCESSING.name());
        latest.setRetryCount(1); // 用户手动重试后变为 1

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(transcriptionMapper.selectStalledTranscription(any(), anyInt())).thenReturn(List.of(stalled));
        when(transcriptionMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(transcriptionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(latest);

        scheduler.compensate();
        Thread.sleep(200);

        // 检测到 retryCount 变化，应跳过递增 compensationAttempts
        verify(transcriptionMapper, times(1)).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    // ==================== 边界场景测试 ====================

    @Test
    void testCompensate_ProcessAtIsNull_StillProcessed() throws InterruptedException {
        MediaTranscription stalled = createStalledTranscription(MEDIA_ID, 0, 0);
        stalled.setProcessAt(null); // processAt 为 null 的边界情况

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(transcriptionMapper.selectStalledTranscription(any(), anyInt())).thenReturn(List.of(stalled));
        when(transcriptionMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        scheduler.compensate();

        verify(transcriptionMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(aiService).asyncTranscribe(MEDIA_ID, false);
    }

    // ==================== 辅助方法 ====================

    private MediaTranscription createStalledTranscription(Long mediaId, int compensationAttempts, int version) {
        MediaTranscription transcription = new MediaTranscription();
        transcription.setMediaId(mediaId);
        transcription.setStatus(AiStatus.PROCESSING.name());
        transcription.setProcessAt(STALE_TIME);
        transcription.setCompensationAttempts(compensationAttempts);
        transcription.setRetryCount(0);
        transcription.setVersion(version);
        return transcription;
    }
}
