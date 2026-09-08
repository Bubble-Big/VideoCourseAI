package com.example.server.service;

import com.example.server.common.AiStatus;
import com.example.server.common.GateOutcome;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.strategy.AiAnalysisStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AiService 单元测试
 * <p>
 * 覆盖问题 2 的核心改造：asyncAnalyze 返回 CompletableFuture<GateOutcome>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock
    private MediaFileMapper mediaFileMapper;

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

    @InjectMocks
    private AiService aiService;

    private static final Long MEDIA_ID = 1L;
    private static final String CONTENT_HASH = "test-hash";

    @BeforeEach
    void setUp() {
        // 由于 @Async 在单元测试中不会真正异步执行，我们需要模拟同步行为
    }

    // ==================== 问题 2：asyncAnalyze 返回值测试 ====================

    @Test
    void testAsyncAnalyze_ReturnsCompletableFuture() {
        MediaFile mediaFile = createMediaFile();
        when(mediaService.contentHash(MEDIA_ID)).thenReturn(CONTENT_HASH);
        when(contentTaskGate.inAnalysisLock(eq(CONTENT_HASH), any()))
                .thenReturn(GateOutcome.PROCEED);

        CompletableFuture<GateOutcome> future = aiService.asyncAnalyze(MEDIA_ID);

        assertNotNull(future);
        assertInstanceOf(CompletableFuture.class, future);
    }

    @Test
    void testAsyncAnalyze_ReturnsPROCEED_OnSuccess() throws ExecutionException, InterruptedException {
        MediaFile mediaFile = createMediaFile();
        when(mediaService.contentHash(MEDIA_ID)).thenReturn(CONTENT_HASH);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(mediaFile);
        when(contentTaskGate.resolveAnalysis(mediaFile, CONTENT_HASH)).thenReturn(false);
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

        CompletableFuture<GateOutcome> future = aiService.asyncAnalyze(MEDIA_ID);

        assertEquals(GateOutcome.PROCEED, future.get());
    }

    @Test
    void testAsyncAnalyze_ReturnsREUSE_OnCacheHit() throws ExecutionException, InterruptedException {
        MediaFile mediaFile = createMediaFile();
        when(mediaService.contentHash(MEDIA_ID)).thenReturn(CONTENT_HASH);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(mediaFile);
        when(contentTaskGate.resolveAnalysis(mediaFile, CONTENT_HASH)).thenReturn(true);
        when(contentTaskGate.inAnalysisLock(eq(CONTENT_HASH), any()))
                .thenAnswer(invocation -> {
                    return invocation.getArgument(1, java.util.function.Supplier.class).get();
                });

        CompletableFuture<GateOutcome> future = aiService.asyncAnalyze(MEDIA_ID);

        assertEquals(GateOutcome.REUSE, future.get());
    }

    @Test
    void testAsyncAnalyze_ReturnsDEFER_OnLockTimeout() throws ExecutionException, InterruptedException {
        when(mediaService.contentHash(MEDIA_ID)).thenReturn(CONTENT_HASH);
        when(contentTaskGate.inAnalysisLock(eq(CONTENT_HASH), any()))
                .thenReturn(GateOutcome.DEFER);

        CompletableFuture<GateOutcome> future = aiService.asyncAnalyze(MEDIA_ID);

        assertEquals(GateOutcome.DEFER, future.get());
    }

    // ==================== 乐观锁版本号测试 ====================

    @Test
    void testAsyncAnalyze_WithVersionField_UpdatesSuccessfully() {
        MediaFile mediaFile = createMediaFile();
        mediaFile.setVersion(1);
        when(mediaService.contentHash(MEDIA_ID)).thenReturn(CONTENT_HASH);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(mediaFile);
        when(contentTaskGate.resolveAnalysis(mediaFile, CONTENT_HASH)).thenReturn(false);
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

        aiService.asyncAnalyze(MEDIA_ID);

        ArgumentCaptor<MediaFile> captor = ArgumentCaptor.forClass(MediaFile.class);
        verify(mediaFileMapper, atLeastOnce()).updateById(captor.capture());

        // 验证 version 字段被正确传递（MyBatis-Plus 会自动处理）
        MediaFile updated = captor.getValue();
        assertEquals(1, updated.getVersion());
    }

    // ==================== 辅助方法 ====================

    private MediaFile createMediaFile() {
        MediaFile file = new MediaFile();
        file.setId(MEDIA_ID);
        file.setUserId(1L);
        file.setFilePath("/path/to/video.mp4");
        file.setAiStatus(AiStatus.PENDING.name());
        file.setTranscriptStatus(AiStatus.NONE.name());
        file.setVersion(0);
        file.setCompensationAttempts(0);
        return file;
    }
}
