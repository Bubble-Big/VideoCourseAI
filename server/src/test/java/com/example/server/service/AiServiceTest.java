package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.common.AiStatus;
import com.example.server.common.GateOutcome;
import com.example.server.entity.MediaAiAnalysis;
import com.example.server.entity.MediaFile;
import com.example.server.entity.MediaTranscription;
import com.example.server.mapper.MediaAiAnalysisMapper;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.MediaTranscriptionMapper;
import com.example.server.strategy.AiAnalysisStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AiService 单元测试（子表适配版）
 * <p>
 * 覆盖核心场景：asyncAnalyze 返回 CompletableFuture<GateOutcome>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock
    private MediaFileMapper mediaFileMapper;

    @Mock
    private MediaAiAnalysisMapper aiAnalysisMapper;

    @Mock
    private MediaTranscriptionMapper transcriptionMapper;

    @Mock
    private AiAnalysisStrategy aiAnalysisStrategy;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private MediaService mediaService;

    @Mock
    private FailedAnalysisTaskService failedTaskService;

    @Mock
    private ContentTaskGate contentTaskGate;

    @Mock
    private TaskEventService taskEventService;

    @InjectMocks
    private AiService aiService;

    private static final Long MEDIA_ID = 1L;
    private static final String CONTENT_HASH = "test-hash";

    @BeforeEach
    void setUp() {
        // 由于 @Async 在单元测试中不会真正异步执行，我们需要模拟同步行为
    }

    // ==================== asyncAnalyze 返回值测试 ====================

    @Test
    void testAsyncAnalyze_ReturnsCompletableFuture() {
        when(mediaService.contentHash(MEDIA_ID)).thenReturn(CONTENT_HASH);
        when(contentTaskGate.inAnalysisLock(eq(CONTENT_HASH), any()))
                .thenReturn(GateOutcome.DEFER);

        CompletableFuture<GateOutcome> future = aiService.asyncAnalyze(MEDIA_ID, false);

        assertNotNull(future);
        assertInstanceOf(CompletableFuture.class, future);
    }

    @Test
    void testAsyncAnalyze_ReturnsPROCEED_OnSuccess() throws ExecutionException, InterruptedException {
        MediaFile mediaFile = createMediaFile();
        MediaAiAnalysis aiAnalysis = createAiAnalysis();
        aiAnalysis.setStatus(AiStatus.NONE.name());
        MediaTranscription transcription = createTranscription();

        when(mediaService.contentHash(MEDIA_ID)).thenReturn(CONTENT_HASH);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(mediaFile);
        when(aiAnalysisMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiAnalysis);
        when(aiAnalysisMapper.updateById(any(MediaAiAnalysis.class))).thenReturn(1);
        when(transcriptionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(transcription);
        when(transcriptionMapper.updateById(any(MediaTranscription.class))).thenReturn(1);
        when(contentTaskGate.resolveAnalysis(mediaFile, CONTENT_HASH)).thenReturn(false);
        when(contentTaskGate.resolveTranscript(mediaFile, CONTENT_HASH)).thenReturn(null);
        when(contentTaskGate.inTranscribeLock(eq(CONTENT_HASH), any()))
                .thenAnswer(invocation -> {
                    return invocation.getArgument(1, java.util.function.Supplier.class).get();
                });
        when(aiAnalysisStrategy.transcribe(anyString())).thenReturn("转写文本");
        when(aiAnalysisStrategy.generateSummaryFromText(anyString())).thenReturn("总结内容");
        when(contentTaskGate.inAnalysisLock(eq(CONTENT_HASH), any()))
                .thenAnswer(invocation -> {
                    return invocation.getArgument(1, java.util.function.Supplier.class).get();
                });

        CompletableFuture<GateOutcome> future = aiService.asyncAnalyze(MEDIA_ID, false);

        assertEquals(GateOutcome.PROCEED, future.get());
    }

    @Test
    void testAsyncAnalyze_ReturnsREUSE_OnCacheHit() throws ExecutionException, InterruptedException {
        MediaFile mediaFile = createMediaFile();
        MediaAiAnalysis aiAnalysis = createAiAnalysis();

        when(mediaService.contentHash(MEDIA_ID)).thenReturn(CONTENT_HASH);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(mediaFile);
        when(aiAnalysisMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiAnalysis);
        when(contentTaskGate.resolveAnalysis(mediaFile, CONTENT_HASH)).thenReturn(true);
        when(contentTaskGate.inAnalysisLock(eq(CONTENT_HASH), any()))
                .thenAnswer(invocation -> {
                    return invocation.getArgument(1, java.util.function.Supplier.class).get();
                });

        CompletableFuture<GateOutcome> future = aiService.asyncAnalyze(MEDIA_ID, false);

        assertEquals(GateOutcome.REUSE, future.get());
    }

    @Test
    void testAsyncAnalyze_ReturnsDEFER_OnLockTimeout() throws ExecutionException, InterruptedException {
        when(mediaService.contentHash(MEDIA_ID)).thenReturn(CONTENT_HASH);
        when(contentTaskGate.inAnalysisLock(eq(CONTENT_HASH), any()))
                .thenReturn(GateOutcome.DEFER);

        CompletableFuture<GateOutcome> future = aiService.asyncAnalyze(MEDIA_ID, false);

        assertEquals(GateOutcome.DEFER, future.get());
    }

    // ==================== 乐观锁版本号测试 ====================

    @Test
    void testAsyncAnalyze_WithVersionField_UpdatesSuccessfully() {
        MediaFile mediaFile = createMediaFile();
        MediaAiAnalysis aiAnalysis = createAiAnalysis();
        aiAnalysis.setVersion(1);
        aiAnalysis.setStatus(AiStatus.NONE.name());
        MediaTranscription transcription = createTranscription();

        when(mediaService.contentHash(MEDIA_ID)).thenReturn(CONTENT_HASH);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(mediaFile);
        when(aiAnalysisMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiAnalysis);
        when(aiAnalysisMapper.updateById(any(MediaAiAnalysis.class))).thenReturn(1);
        when(transcriptionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(transcription);
        when(transcriptionMapper.updateById(any(MediaTranscription.class))).thenReturn(1);
        when(contentTaskGate.resolveAnalysis(mediaFile, CONTENT_HASH)).thenReturn(false);
        when(contentTaskGate.resolveTranscript(mediaFile, CONTENT_HASH)).thenReturn(null);
        when(contentTaskGate.inTranscribeLock(eq(CONTENT_HASH), any()))
                .thenAnswer(invocation -> {
                    return invocation.getArgument(1, java.util.function.Supplier.class).get();
                });
        when(aiAnalysisStrategy.transcribe(anyString())).thenReturn("转写文本");
        when(aiAnalysisStrategy.generateSummaryFromText(anyString())).thenReturn("总结内容");
        when(contentTaskGate.inAnalysisLock(eq(CONTENT_HASH), any()))
                .thenAnswer(invocation -> {
                    return invocation.getArgument(1, java.util.function.Supplier.class).get();
                });

        aiService.asyncAnalyze(MEDIA_ID, false);

        // 验证 version 字段被正确使用（MyBatis-Plus 会自动处理）
        verify(aiAnalysisMapper, atLeastOnce()).selectOne(any(LambdaQueryWrapper.class));
    }

    // ==================== 辅助方法 ====================

    private MediaFile createMediaFile() {
        MediaFile file = new MediaFile();
        file.setId(MEDIA_ID);
        file.setUserId(1L);
        file.setFilePath("/path/to/video.mp4");
        return file;
    }

    private MediaAiAnalysis createAiAnalysis() {
        MediaAiAnalysis analysis = new MediaAiAnalysis();
        analysis.setId(1L);
        analysis.setMediaId(MEDIA_ID);
        analysis.setStatus(AiStatus.PENDING.name());
        analysis.setAttempts(0);
        analysis.setCompensationAttempts(0);
        analysis.setRetryCount(0);
        analysis.setVersion(0);
        return analysis;
    }

    private MediaTranscription createTranscription() {
        MediaTranscription transcription = new MediaTranscription();
        transcription.setId(1L);
        transcription.setMediaId(MEDIA_ID);
        transcription.setStatus(AiStatus.NONE.name());
        transcription.setAttempts(0);
        transcription.setCompensationAttempts(0);
        transcription.setRetryCount(0);
        transcription.setVersion(0);
        return transcription;
    }
}
