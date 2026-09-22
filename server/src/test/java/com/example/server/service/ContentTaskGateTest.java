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
import com.example.server.utils.AnalysisTaskKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ContentTaskGate 单元测试（子表适配版）
 * <p>
 * 覆盖核心逻辑：
 * - 提交侧幂等键（tryMarkSubmitting / rollbackSubmitting）
 * - 分析锁（inAnalysisLock）的 PROCEED / DEFER 分支
 * - 转写锁（inTranscribeLock）的 PROCEED / DEFER 分支
 * - 分析结果复用（resolveAnalysis）的幂等 / Redis / DB 三条路径
 * - 转写结果复用（resolveTranscript）的幂等 / Redis / DB 三条路径
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ContentTaskGateTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private MediaFileMapper mediaFileMapper;

    @Mock
    private MediaAiAnalysisMapper aiAnalysisMapper;

    @Mock
    private MediaTranscriptionMapper transcriptionMapper;

    @Mock
    private TaskEventService taskEventService;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private RLock rLock;

    @InjectMocks
    private ContentTaskGate gate;

    private static final String CONTENT_HASH = "d41d8cd98f00b204e9800998ecf8427e";  // 32位合法MD5
    private static final Long MEDIA_ID = 1L;
    private static final Long OWNER_ID = 2L;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(gate, "analysisLockWaitSeconds", 600);
        ReflectionTestUtils.setField(gate, "contextLockWaitSeconds", 600);
    }

    // ==================== 提交侧幂等键测试 ====================

    @Test
    void testTryMarkSubmitting_Success() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);

        boolean result = gate.tryMarkSubmitting(CONTENT_HASH, MEDIA_ID);

        assertTrue(result);
        verify(valueOps).setIfAbsent(
                eq(AnalysisTaskKeys.active(CONTENT_HASH)),
                eq(String.valueOf(MEDIA_ID)),
                any());
    }

    @Test
    void testTryMarkSubmitting_ConcurrentSubmit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        boolean result = gate.tryMarkSubmitting(CONTENT_HASH, MEDIA_ID);

        assertFalse(result);
    }

    @Test
    void testRollbackSubmitting() {
        gate.rollbackSubmitting(CONTENT_HASH);

        verify(redisTemplate).delete(AnalysisTaskKeys.active(CONTENT_HASH));
    }

    // ==================== 分析锁测试 ====================

    @Test
    void testInAnalysisLock_Proceed() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        GateOutcome result = gate.inAnalysisLock(CONTENT_HASH, () -> GateOutcome.PROCEED);

        assertEquals(GateOutcome.PROCEED, result);
        verify(rLock).unlock();
    }

    @Test
    void testInAnalysisLock_Timeout() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        GateOutcome result = gate.inAnalysisLock(CONTENT_HASH, () -> GateOutcome.PROCEED);

        assertEquals(GateOutcome.DEFER, result);
        verify(rLock, never()).unlock();
    }

    @Test
    void testInAnalysisLock_Interrupted() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());

        GateOutcome result = gate.inAnalysisLock(CONTENT_HASH, () -> GateOutcome.PROCEED);

        assertEquals(GateOutcome.DEFER, result);
        assertTrue(Thread.interrupted());
    }

    // ==================== 转写锁测试 ====================

    @Test
    void testInTranscribeLock_Proceed() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(rLock.isHeldByCurrentThread()).thenReturn(true);

        GateOutcome result = gate.inTranscribeLock(CONTENT_HASH, () -> GateOutcome.PROCEED);

        assertEquals(GateOutcome.PROCEED, result);
        verify(rLock).unlock();
    }

    @Test
    void testInTranscribeLock_Defer() throws InterruptedException {
        when(redissonClient.getLock(anyString())).thenReturn(rLock);
        when(rLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        GateOutcome result = gate.inTranscribeLock(CONTENT_HASH, () -> GateOutcome.PROCEED);

        assertEquals(GateOutcome.DEFER, result);
    }

    // ==================== 分析结果复用测试 ====================

    @Test
    void testResolveAnalysis_Idempotent() {
        MediaFile mediaFile = createMediaFile(MEDIA_ID);
        MediaAiAnalysis currentAnalysis = createAnalysis(MEDIA_ID);
        currentAnalysis.setStatus(AiStatus.SUCCESS.name());
        currentAnalysis.setSummary("已有总结");

        when(aiAnalysisMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(currentAnalysis);

        boolean result = gate.resolveAnalysis(mediaFile, CONTENT_HASH);

        assertTrue(result);
    }

    @Test
    void testResolveAnalysis_ReuseFromRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        MediaFile mediaFile = createMediaFile(MEDIA_ID);
        MediaAiAnalysis currentAnalysis = createAnalysis(MEDIA_ID);
        MediaAiAnalysis ownerAnalysis = createAnalysis(OWNER_ID);
        ownerAnalysis.setStatus(AiStatus.SUCCESS.name());
        ownerAnalysis.setSummary("归属总结");
        MediaTranscription ownerTranscription = createTranscription(OWNER_ID);
        ownerTranscription.setStatus(AiStatus.SUCCESS.name());
        ownerTranscription.setTranscriptText("归属转写");

        when(aiAnalysisMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(currentAnalysis)  // 当前分析记录
                .thenReturn(ownerAnalysis);   // 归属分析记录
        when(valueOps.get(AnalysisTaskKeys.completedOwner(CONTENT_HASH)))
                .thenReturn(String.valueOf(OWNER_ID));
        when(transcriptionMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(ownerTranscription)  // 归属转写记录
                .thenReturn(null);               // 当前转写记录不存在

        boolean result = gate.resolveAnalysis(mediaFile, CONTENT_HASH);

        assertTrue(result);
        verify(aiAnalysisMapper).updateById(argThat((MediaAiAnalysis analysis) ->
            analysis != null && "归属总结".equals(analysis.getSummary())
        ));
        verify(transcriptionMapper).insert(argThat((MediaTranscription transcription) ->
            transcription != null && "归属转写".equals(transcription.getTranscriptText())
        ));
    }

    @Test
    void testResolveAnalysis_ReuseFromDB() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        MediaFile mediaFile = createMediaFile(MEDIA_ID);
        mediaFile.setFileMd5(CONTENT_HASH);
        MediaAiAnalysis currentAnalysis = createAnalysis(MEDIA_ID);
        MediaAiAnalysis ownerAnalysis = createAnalysis(OWNER_ID);
        ownerAnalysis.setStatus(AiStatus.SUCCESS.name());
        ownerAnalysis.setSummary("DB 归属总结");
        MediaTranscription ownerTranscription = createTranscription(OWNER_ID);
        ownerTranscription.setStatus(AiStatus.SUCCESS.name());
        ownerTranscription.setTranscriptText("DB 归属转写");

        when(aiAnalysisMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(currentAnalysis);
        when(valueOps.get(anyString())).thenReturn(null);
        when(aiAnalysisMapper.selectCompletedAnalysisByMd5(CONTENT_HASH, MEDIA_ID)).thenReturn(ownerAnalysis);
        when(transcriptionMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(ownerTranscription)  // 归属转写记录
                .thenReturn(null);               // 当前转写记录不存在

        boolean result = gate.resolveAnalysis(mediaFile, CONTENT_HASH);

        assertTrue(result);
        verify(aiAnalysisMapper).updateById(argThat((MediaAiAnalysis analysis) ->
            analysis != null && "DB 归属总结".equals(analysis.getSummary())
        ));
        verify(valueOps).set(eq(AnalysisTaskKeys.completedOwner(CONTENT_HASH)),
                eq(String.valueOf(OWNER_ID)), any());
    }

    @Test
    void testResolveAnalysis_NoReuse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        MediaFile mediaFile = createMediaFile(MEDIA_ID);
        mediaFile.setFileMd5(CONTENT_HASH);
        MediaAiAnalysis currentAnalysis = createAnalysis(MEDIA_ID);

        when(aiAnalysisMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(currentAnalysis);
        when(valueOps.get(anyString())).thenReturn(null);
        when(aiAnalysisMapper.selectCompletedAnalysisByMd5(CONTENT_HASH, MEDIA_ID)).thenReturn(null);

        boolean result = gate.resolveAnalysis(mediaFile, CONTENT_HASH);

        assertFalse(result);
        verify(aiAnalysisMapper, never()).updateById(any(MediaAiAnalysis.class));
    }

    // ==================== 转写结果复用测试 ====================

    @Test
    void testResolveTranscript_Idempotent() {
        MediaFile mediaFile = createMediaFile(MEDIA_ID);
        MediaTranscription currentTranscription = createTranscription(MEDIA_ID);
        currentTranscription.setStatus(AiStatus.SUCCESS.name());
        currentTranscription.setTranscriptText("已有转写");

        when(transcriptionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(currentTranscription);

        String result = gate.resolveTranscript(mediaFile, CONTENT_HASH);

        assertEquals("已有转写", result);
    }

    @Test
    void testResolveTranscript_ReuseFromRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        MediaFile mediaFile = createMediaFile(MEDIA_ID);
        MediaTranscription currentTranscription = createTranscription(MEDIA_ID);
        MediaTranscription ownerTranscription = createTranscription(OWNER_ID);
        ownerTranscription.setStatus(AiStatus.SUCCESS.name());
        ownerTranscription.setTranscriptText("归属转写");

        when(transcriptionMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(currentTranscription)  // 当前转写记录
                .thenReturn(ownerTranscription);   // 归属转写记录
        when(valueOps.get(AnalysisTaskKeys.contextOwner(CONTENT_HASH)))
                .thenReturn(String.valueOf(OWNER_ID));

        String result = gate.resolveTranscript(mediaFile, CONTENT_HASH);

        assertEquals("归属转写", result);
        verify(transcriptionMapper).updateById(argThat((MediaTranscription transcription) ->
            transcription != null && "归属转写".equals(transcription.getTranscriptText())
        ));
    }

    @Test
    void testResolveTranscript_ReuseFromDB() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        MediaFile mediaFile = createMediaFile(MEDIA_ID);
        mediaFile.setFileMd5(CONTENT_HASH);
        MediaTranscription currentTranscription = createTranscription(MEDIA_ID);
        MediaTranscription ownerTranscription = createTranscription(OWNER_ID);
        ownerTranscription.setStatus(AiStatus.SUCCESS.name());
        ownerTranscription.setTranscriptText("DB 归属转写");

        when(transcriptionMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(currentTranscription)  // 当前转写记录
                .thenReturn(ownerTranscription);   // 归属转写记录
        when(valueOps.get(anyString())).thenReturn(null);
        when(transcriptionMapper.selectCompletedTranscriptByMd5(CONTENT_HASH, MEDIA_ID))
                .thenReturn(ownerTranscription);

        String result = gate.resolveTranscript(mediaFile, CONTENT_HASH);

        assertEquals("DB 归属转写", result);
        verify(transcriptionMapper).updateById(argThat((MediaTranscription transcription) ->
            transcription != null && "DB 归属转写".equals(transcription.getTranscriptText())
        ));
        verify(valueOps).set(eq(AnalysisTaskKeys.contextOwner(CONTENT_HASH)),
                eq(String.valueOf(OWNER_ID)), any());
    }

    @Test
    void testResolveTranscript_NoReuse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        MediaFile mediaFile = createMediaFile(MEDIA_ID);
        mediaFile.setFileMd5(CONTENT_HASH);
        MediaTranscription currentTranscription = createTranscription(MEDIA_ID);

        when(transcriptionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(currentTranscription);
        when(valueOps.get(anyString())).thenReturn(null);
        when(transcriptionMapper.selectCompletedTranscriptByMd5(CONTENT_HASH, MEDIA_ID)).thenReturn(null);

        String result = gate.resolveTranscript(mediaFile, CONTENT_HASH);

        assertNull(result);
        verify(transcriptionMapper, never()).updateById(any(MediaTranscription.class));
    }

    // ==================== 辅助方法 ====================

    private MediaFile createMediaFile(Long id) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setFileMd5("default-hash");
        return file;
    }

    private MediaAiAnalysis createAnalysis(Long mediaId) {
        MediaAiAnalysis analysis = new MediaAiAnalysis();
        analysis.setId(mediaId);
        analysis.setMediaId(mediaId);
        analysis.setStatus(AiStatus.NONE.name());
        analysis.setAttempts(0);
        analysis.setCompensationAttempts(0);
        analysis.setRetryCount(0);
        analysis.setVersion(0);
        return analysis;
    }

    private MediaTranscription createTranscription(Long mediaId) {
        MediaTranscription transcription = new MediaTranscription();
        transcription.setId(mediaId);
        transcription.setMediaId(mediaId);
        transcription.setStatus(AiStatus.NONE.name());
        transcription.setAttempts(0);
        transcription.setCompensationAttempts(0);
        transcription.setRetryCount(0);
        transcription.setVersion(0);
        return transcription;
    }
}
