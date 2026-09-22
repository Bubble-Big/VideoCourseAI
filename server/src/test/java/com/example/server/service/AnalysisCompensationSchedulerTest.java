package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.common.AiStatus;
import com.example.server.common.GateOutcome;
import com.example.server.entity.MediaAiAnalysis;
import com.example.server.exception.AiAnalysisException;
import com.example.server.mapper.MediaAiAnalysisMapper;
import com.example.server.mapper.MediaFileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * AnalysisCompensationScheduler 单元测试（子表适配版）
 * <p>
 * 覆盖表拆分后的核心场景：
 * - 场景 1：直接查询子表 media_ai_analysis 扫描卡死记录
 * - 场景 2：乐观锁版本冲突检测（防止并发更新丢失）
 * - 场景 3：GateOutcome 绑定（DEFER/REUSE 不消耗重试次数）
 * - 场景 4：达到最大重试次数后标记 FAILED
 * - 场景 5：单条记录异常隔离（不中断批量处理）
 * - 场景 6：分布式锁互斥
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class AnalysisCompensationSchedulerTest {

    @Mock
    private MediaFileMapper mediaFileMapper;

    @Mock
    private MediaAiAnalysisMapper aiAnalysisMapper;

    @Mock
    private AiService aiService;

    @Mock
    private FailedAnalysisTaskService failedTaskService;

    @Mock
    private TaskEventService taskEventService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock schedulerLock;

    private AnalysisCompensationScheduler scheduler;

    private static final Long MEDIA_ID = 100L;
    private static final LocalDateTime STALE_TIME = LocalDateTime.now().minusMinutes(30);

    @BeforeEach
    void setUp() {
        scheduler = new AnalysisCompensationScheduler(
            mediaFileMapper,
            aiService,
            failedTaskService,
            taskEventService,
            redissonClient,
            aiAnalysisMapper
        );
        ReflectionTestUtils.setField(scheduler, "thresholdMinutes", 20L);
        ReflectionTestUtils.setField(scheduler, "maxAttempts", 3);
    }

    // ==================== 场景 6：分布式锁互斥 ====================

    @Test
    void testCompensate_DistributedLock_Success() throws InterruptedException {
        when(redissonClient.getLock("lock:scheduler:analysis-compensation")).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(0L, 50L, TimeUnit.SECONDS)).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(aiAnalysisMapper.selectStalledAnalysis(any(LocalDateTime.class), anyInt()))
            .thenReturn(Collections.emptyList());

        scheduler.compensate();

        verify(schedulerLock).tryLock(0L, 50L, TimeUnit.SECONDS);
        verify(aiAnalysisMapper).selectStalledAnalysis(any(LocalDateTime.class), eq(100));
        verify(schedulerLock).unlock();
    }

    @Test
    void testCompensate_DistributedLock_CannotAcquire() throws InterruptedException {
        when(redissonClient.getLock("lock:scheduler:analysis-compensation")).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        scheduler.compensate();

        verify(aiAnalysisMapper, never()).selectStalledAnalysis(any(), anyInt());
        verify(schedulerLock, never()).unlock();
    }

    @Test
    void testCompensate_DistributedLock_Interrupted() throws InterruptedException {
        when(redissonClient.getLock("lock:scheduler:analysis-compensation")).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
            .thenThrow(new InterruptedException());

        scheduler.compensate();

        verify(aiAnalysisMapper, never()).selectStalledAnalysis(any(), anyInt());
        assertTrue(Thread.interrupted());
    }

    // ==================== 场景 1：扫描子表卡死记录 ====================

    @Test
    void testCompensate_ScanStalledTasks_FromChildTable() throws InterruptedException {
        MediaAiAnalysis stalled = createStalledAnalysis(MEDIA_ID, 0, 0);

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(aiAnalysisMapper.selectStalledAnalysis(any(LocalDateTime.class), eq(100)))
            .thenReturn(List.of(stalled));
        when(aiAnalysisMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(aiService.asyncAnalyze(MEDIA_ID, false))
            .thenReturn(CompletableFuture.completedFuture(GateOutcome.PROCEED));

        scheduler.compensate();

        verify(aiAnalysisMapper).selectStalledAnalysis(any(LocalDateTime.class), eq(100));
        verify(aiAnalysisMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(aiService).asyncAnalyze(MEDIA_ID, false);
    }

    // ==================== 场景 2：乐观锁版本冲突 ====================

    @Test
    void testCompensateOne_VersionConflict_SkipUpdate() throws InterruptedException {
        MediaAiAnalysis stalled = createStalledAnalysis(MEDIA_ID, 5, 0);

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(aiAnalysisMapper.selectStalledAnalysis(any(), anyInt())).thenReturn(List.of(stalled));
        when(aiAnalysisMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0); // 版本冲突

        scheduler.compensate();

        verify(aiAnalysisMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(aiService, never()).asyncAnalyze(anyLong(), anyBoolean());
    }

    // ==================== 场景 3：GateOutcome 绑定 ====================

    @Test
    void testCompensateOne_DEFER_NotConsumeAttempts() throws InterruptedException {
        MediaAiAnalysis stalled = createStalledAnalysis(MEDIA_ID, 0, 0);

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(aiAnalysisMapper.selectStalledAnalysis(any(), anyInt())).thenReturn(List.of(stalled));
        when(aiAnalysisMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(aiService.asyncAnalyze(MEDIA_ID, false))
            .thenReturn(CompletableFuture.completedFuture(GateOutcome.DEFER));

        scheduler.compensate();
        Thread.sleep(100); // 等待异步回调

        verify(aiService).asyncAnalyze(MEDIA_ID, false);
        // DEFER 时 shouldSkipIncrement 返回 true，不会查询子表递增计数
        verify(aiAnalysisMapper, times(1)).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void testCompensateOne_REUSE_NotConsumeAttempts() throws InterruptedException {
        MediaAiAnalysis stalled = createStalledAnalysis(MEDIA_ID, 0, 0);

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(aiAnalysisMapper.selectStalledAnalysis(any(), anyInt())).thenReturn(List.of(stalled));
        when(aiAnalysisMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(aiService.asyncAnalyze(MEDIA_ID, false))
            .thenReturn(CompletableFuture.completedFuture(GateOutcome.REUSE));

        scheduler.compensate();
        Thread.sleep(100);

        verify(aiService).asyncAnalyze(MEDIA_ID, false);
        verify(aiAnalysisMapper, times(1)).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void testCompensateOne_PROCEED_ConsumeAttempts() throws InterruptedException {
        MediaAiAnalysis stalled = createStalledAnalysis(MEDIA_ID, 0, 1);
        MediaAiAnalysis latest = createStalledAnalysis(MEDIA_ID, 0, 1);
        latest.setStatus(AiStatus.PROCESSING.name());

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(aiAnalysisMapper.selectStalledAnalysis(any(), anyInt())).thenReturn(List.of(stalled));
        when(aiAnalysisMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(aiService.asyncAnalyze(MEDIA_ID, false))
            .thenReturn(CompletableFuture.completedFuture(GateOutcome.PROCEED));
        when(aiAnalysisMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(latest);

        scheduler.compensate();
        Thread.sleep(200); // 等待异步回调

        verify(aiService).asyncAnalyze(MEDIA_ID, false);
        // PROCEED 时会重新查询子表并递增 compensation_attempts
        verify(aiAnalysisMapper, atLeast(2)).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    // ==================== 场景 4：达到最大重试次数 ====================

    @Test
    void testIncrementAttempts_ReachMaxAttempts_MarkFailed() throws InterruptedException {
        MediaAiAnalysis stalled = createStalledAnalysis(MEDIA_ID, 2, 1);
        MediaAiAnalysis latest = createStalledAnalysis(MEDIA_ID, 2, 1);
        latest.setStatus(AiStatus.PROCESSING.name());

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(aiAnalysisMapper.selectStalledAnalysis(any(), anyInt())).thenReturn(List.of(stalled));
        when(aiAnalysisMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(aiService.asyncAnalyze(MEDIA_ID, false))
            .thenReturn(CompletableFuture.completedFuture(GateOutcome.PROCEED));
        when(aiAnalysisMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(latest);

        scheduler.compensate();
        Thread.sleep(200);

        // 验证达到 maxAttempts=3 时调用失败处理
        verify(failedTaskService).record(eq(MEDIA_ID), any(AiAnalysisException.class), eq(3));
        verify(taskEventService).publishAnalysis(eq(MEDIA_ID), eq(AiStatus.FAILED.name()), isNull(), anyString());
    }

    // ==================== 场景 5：单条记录异常隔离 ====================

    @Test
    void testCompensate_SingleRecordException_NotInterruptLoop() throws InterruptedException {
        MediaAiAnalysis analysis1 = createStalledAnalysis(1L, 0, 0);
        MediaAiAnalysis analysis2 = createStalledAnalysis(2L, 0, 0);
        MediaAiAnalysis analysis3 = createStalledAnalysis(3L, 0, 0);

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(aiAnalysisMapper.selectStalledAnalysis(any(), anyInt()))
            .thenReturn(Arrays.asList(analysis1, analysis2, analysis3));

        // 使用 answer 来区分不同的 mediaId
        when(aiAnalysisMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenAnswer(invocation -> {
            LambdaUpdateWrapper<?> wrapper = invocation.getArgument(1);
            String sql = wrapper.getSqlSegment();
            if (sql != null && sql.contains("media_id = 2")) {
                throw new RuntimeException("模拟数据库异常");
            }
            return 1;
        });

        when(aiService.asyncAnalyze(1L, false))
            .thenReturn(CompletableFuture.completedFuture(GateOutcome.PROCEED));
        when(aiService.asyncAnalyze(3L, false))
            .thenReturn(CompletableFuture.completedFuture(GateOutcome.PROCEED));

        scheduler.compensate();

        // 验证即使 analysis2 异常，analysis1 和 analysis3 仍然被处理
        verify(aiService).asyncAnalyze(1L, false);
        verify(aiService).asyncAnalyze(3L, false);
        verify(schedulerLock).unlock();
    }

    @Test
    void testCompensate_EmptyList_NoProcessing() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(aiAnalysisMapper.selectStalledAnalysis(any(), anyInt())).thenReturn(Collections.emptyList());

        scheduler.compensate();

        verify(aiService, never()).asyncAnalyze(anyLong(), anyBoolean());
        verify(schedulerLock).unlock();
    }

    // ==================== retryCount 冲突检测 ====================

    @Test
    void testIncrementAttempts_RetryCountChanged_SkipIncrement() throws InterruptedException {
        MediaAiAnalysis stalled = createStalledAnalysis(MEDIA_ID, 0, 1);
        stalled.setRetryCount(0); // 快照时 retryCount=0

        MediaAiAnalysis latest = createStalledAnalysis(MEDIA_ID, 0, 1);
        latest.setStatus(AiStatus.PROCESSING.name());
        latest.setRetryCount(1); // 用户手动重试后变为 1

        when(redissonClient.getLock(anyString())).thenReturn(schedulerLock);
        when(schedulerLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(schedulerLock.isHeldByCurrentThread()).thenReturn(true);
        when(aiAnalysisMapper.selectStalledAnalysis(any(), anyInt())).thenReturn(List.of(stalled));
        when(aiAnalysisMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(aiService.asyncAnalyze(MEDIA_ID, false))
            .thenReturn(CompletableFuture.completedFuture(GateOutcome.PROCEED));
        when(aiAnalysisMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(latest);

        scheduler.compensate();
        Thread.sleep(200);

        // 检测到 retryCount 变化，应跳过递增 compensationAttempts
        verify(aiAnalysisMapper, times(1)).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    // ==================== 辅助方法 ====================

    private MediaAiAnalysis createStalledAnalysis(Long mediaId, int compensationAttempts, int version) {
        MediaAiAnalysis analysis = new MediaAiAnalysis();
        analysis.setMediaId(mediaId);
        analysis.setStatus(AiStatus.PROCESSING.name());
        analysis.setProcessAt(STALE_TIME);
        analysis.setCompensationAttempts(compensationAttempts);
        analysis.setRetryCount(0);
        analysis.setVersion(version);
        return analysis;
    }
}
