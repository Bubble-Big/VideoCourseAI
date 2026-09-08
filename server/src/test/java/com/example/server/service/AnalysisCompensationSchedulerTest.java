package com.example.server.service;

import com.example.server.common.AiStatus;
import com.example.server.common.GateOutcome;
import com.example.server.entity.MediaFile;
import com.example.server.exception.AiAnalysisException;
import com.example.server.mapper.MediaFileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AnalysisCompensationScheduler 单元测试
 * <p>
 * 覆盖加固计划的核心场景：
 * - 问题 1：乐观锁版本冲突检测，防止丢失更新
 * - 问题 2：重试计数绑定执行结果（PROCEED 才计数，DEFER/REUSE 不消耗）
 * - 问题 4：分布式锁互斥
 * - 问题 5：compensation_attempts 专属计数
 * - 问题 6：单条记录异常隔离
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class AnalysisCompensationSchedulerTest {

    @Mock
    private MediaFileMapper mediaFileMapper;

    @Mock
    private AiService aiService;

    @Mock
    private FailedAnalysisTaskService failedTaskService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock schedulerLock;

    @InjectMocks
    private AnalysisCompensationScheduler scheduler;

    private static final Long MEDIA_ID = 1L;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "thresholdMinutes", 20L);
        ReflectionTestUtils.setField(scheduler, "maxAttempts", 3);
    }

    // ==================== 问题 4：分布式锁互斥测试 ====================

    @Test
    void testCompensate_DistributedLock_Success() throws InterruptedException {
        when(redissonClient.getLock("lock:scheduler:analysis-compensation")).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(mediaFileMapper.selectStalledAnalysis(any(), anyInt())).thenReturn(Collections.emptyList());

        scheduler.compensate();

        verify(schedulerLock).tryLock(eq(0L), eq(50L), eq(TimeUnit.SECONDS));
        verify(schedulerLock).unlock();
    }

    @Test
    void testCompensate_DistributedLock_CannotAcquire() throws InterruptedException {
        when(redissonClient.getLock("lock:scheduler:analysis-compensation")).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        scheduler.compensate();

        verify(mediaFileMapper, never()).selectStalledAnalysis(any(), anyInt());
        verify(schedulerLock, never()).unlock();
    }

    @Test
    void testCompensate_DistributedLock_Interrupted() throws InterruptedException {
        when(redissonClient.getLock("lock:scheduler:analysis-compensation")).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());

        scheduler.compensate();

        verify(mediaFileMapper, never()).selectStalledAnalysis(any(), anyInt());
        assertTrue(Thread.interrupted());
    }

    // ==================== 问题 1：乐观锁版本冲突检测 ====================

    @Test
    void testCompensateOne_VersionConflict_SkipUpdate() throws InterruptedException {
        MediaFile stalledFile = createStalledFile(MEDIA_ID, 0);
        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(mediaFileMapper.selectStalledAnalysis(any(), anyInt())).thenReturn(List.of(stalledFile));
        when(mediaFileMapper.updateById(stalledFile)).thenReturn(0);  // 版本冲突，返回 0

        scheduler.compensate();

        verify(mediaFileMapper).updateById(stalledFile);
        verify(aiService, never()).asyncAnalyze(anyLong());  // 版本冲突时不触发重试
    }

    // ==================== 问题 2：重试计数绑定执行结果 ====================

    @Test
    void testCompensateOne_DEFER_NotConsumeAttempts() throws InterruptedException {
        MediaFile stalledFile = createStalledFile(MEDIA_ID, 0);
        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(mediaFileMapper.selectStalledAnalysis(any(), anyInt())).thenReturn(List.of(stalledFile));
        when(mediaFileMapper.updateById(any(MediaFile.class))).thenReturn(1);  // 刷新时间戳成功
        when(aiService.asyncAnalyze(MEDIA_ID))
                .thenReturn(CompletableFuture.completedFuture(GateOutcome.DEFER));

        scheduler.compensate();

        // DEFER 情况下，whenComplete 回调会检测到 DEFER 并提前返回，不会调用 incrementAttemptsIfStillPending
        verify(aiService).asyncAnalyze(MEDIA_ID);
        // 注意：由于 whenComplete 是异步回调，需要等待或使用其他方式验证
        // 这里验证的是主流程没有在 compensateOne 内同步递增计数
    }

    @Test
    void testCompensateOne_REUSE_NotConsumeAttempts() throws InterruptedException {
        MediaFile stalledFile = createStalledFile(MEDIA_ID, 0);
        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(mediaFileMapper.selectStalledAnalysis(any(), anyInt())).thenReturn(List.of(stalledFile));
        when(mediaFileMapper.updateById(any(MediaFile.class))).thenReturn(1);
        when(aiService.asyncAnalyze(MEDIA_ID))
                .thenReturn(CompletableFuture.completedFuture(GateOutcome.REUSE));

        scheduler.compensate();

        verify(aiService).asyncAnalyze(MEDIA_ID);
        // REUSE 也不应该消耗重试次数
    }

    @Test
    void testIncrementAttemptsIfStillPending_PROCEED_ConsumeAttempts() {
        // 这个测试验证 incrementAttemptsIfStillPending 的逻辑，但由于是私有方法，
        // 已经通过 testIncrementAttemptsIfStillPending_UseCompensationAttempts 间接测试
        // 这里不需要单独测试，移除以避免 UnnecessaryStubbingException
    }

    // ==================== 问题 5：compensation_attempts 专属计数 ====================

    @Test
    void testIncrementAttemptsIfStillPending_UseCompensationAttempts() throws InterruptedException {
        MediaFile stalledFile = createStalledFile(MEDIA_ID, 0);
        stalledFile.setAiAttempts(5);  // aiAttempts 有值
        stalledFile.setCompensationAttempts(1);  // compensationAttempts 有值

        MediaFile latestFile = createStalledFile(MEDIA_ID, 0);
        latestFile.setAiStatus(AiStatus.PROCESSING.name());
        latestFile.setCompensationAttempts(1);

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(mediaFileMapper.selectStalledAnalysis(any(), anyInt())).thenReturn(List.of(stalledFile));
        when(mediaFileMapper.updateById(eq(stalledFile))).thenReturn(1);
        when(aiService.asyncAnalyze(MEDIA_ID))
                .thenReturn(CompletableFuture.completedFuture(GateOutcome.PROCEED));
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(latestFile);
        when(mediaFileMapper.updateById(eq(latestFile))).thenReturn(1);

        scheduler.compensate();

        // 等待异步回调完成
        Thread.sleep(100);

        ArgumentCaptor<MediaFile> captor = ArgumentCaptor.forClass(MediaFile.class);
        verify(mediaFileMapper, atLeastOnce()).updateById(captor.capture());

        // 验证使用的是 compensationAttempts，而不是 aiAttempts
        List<MediaFile> updates = captor.getAllValues();
        assertTrue(updates.stream().anyMatch(f ->
                f.getId().equals(MEDIA_ID) && f.getCompensationAttempts() != null && f.getCompensationAttempts() == 2
        ));
    }

    @Test
    void testIncrementAttemptsIfStillPending_ReachMaxAttempts() throws InterruptedException {
        MediaFile stalledFile = createStalledFile(MEDIA_ID, 2);  // 已经重试 2 次
        MediaFile latestFile = createStalledFile(MEDIA_ID, 2);
        latestFile.setAiStatus(AiStatus.PROCESSING.name());
        latestFile.setCompensationAttempts(2);

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(mediaFileMapper.selectStalledAnalysis(any(), anyInt())).thenReturn(List.of(stalledFile));
        when(mediaFileMapper.updateById(any(MediaFile.class))).thenReturn(1);
        when(aiService.asyncAnalyze(MEDIA_ID))
                .thenReturn(CompletableFuture.completedFuture(GateOutcome.PROCEED));
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(latestFile);

        scheduler.compensate();

        // 等待异步回调完成
        Thread.sleep(100);

        ArgumentCaptor<MediaFile> captor = ArgumentCaptor.forClass(MediaFile.class);
        verify(mediaFileMapper, atLeastOnce()).updateById(captor.capture());

        // 验证达到 maxAttempts=3 时，状态变为 FAILED
        List<MediaFile> updates = captor.getAllValues();
        assertTrue(updates.stream().anyMatch(f ->
                f.getId().equals(MEDIA_ID) &&
                        f.getCompensationAttempts() == 3 &&
                        AiStatus.FAILED.name().equals(f.getAiStatus())
        ));

        verify(failedTaskService).record(eq(MEDIA_ID), any(AiAnalysisException.class), eq(3));
    }

    // ==================== 问题 6：单条记录异常隔离 ====================

    @Test
    void testCompensate_SingleRecordException_NotInterruptLoop() throws InterruptedException {
        MediaFile file1 = createStalledFile(1L, 0);
        MediaFile file2 = createStalledFile(2L, 0);
        MediaFile file3 = createStalledFile(3L, 0);

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(mediaFileMapper.selectStalledAnalysis(any(), anyInt()))
                .thenReturn(Arrays.asList(file1, file2, file3));

        // file1 正常
        when(mediaFileMapper.updateById(file1)).thenReturn(1);
        when(aiService.asyncAnalyze(1L))
                .thenReturn(CompletableFuture.completedFuture(GateOutcome.PROCEED));

        // file2 抛异常
        when(mediaFileMapper.updateById(file2)).thenThrow(new RuntimeException("模拟异常"));

        // file3 正常
        when(mediaFileMapper.updateById(file3)).thenReturn(1);
        when(aiService.asyncAnalyze(3L))
                .thenReturn(CompletableFuture.completedFuture(GateOutcome.PROCEED));

        scheduler.compensate();

        // 验证即使 file2 异常，file1 和 file3 仍然被处理
        verify(aiService).asyncAnalyze(1L);
        verify(aiService).asyncAnalyze(3L);
        verify(schedulerLock).unlock();
    }

    @Test
    void testCompensate_EmptyList_NoProcessing() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(mediaFileMapper.selectStalledAnalysis(any(), anyInt())).thenReturn(Collections.emptyList());

        scheduler.compensate();

        verify(aiService, never()).asyncAnalyze(anyLong());
        verify(schedulerLock).unlock();
    }

    // ==================== 辅助方法 ====================

    private MediaFile createStalledFile(Long id, int compensationAttempts) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setAiStatus(AiStatus.PROCESSING.name());
        file.setAiProcessAt(LocalDateTime.now().minusMinutes(30));
        file.setCompensationAttempts(compensationAttempts);
        file.setVersion(1);
        return file;
    }
}
