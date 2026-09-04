package com.example.server.service;

import com.example.server.common.AiStatus;
import com.example.server.common.GateOutcome;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
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
 * ContentTaskGate 单元测试
 * <p>
 * 覆盖 Phase 1 核心逻辑：
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
        mediaFile.setAiStatus(AiStatus.SUCCESS.name());
        mediaFile.setAiSummary("已有总结");

        boolean result = gate.resolveAnalysis(mediaFile, CONTENT_HASH);

        assertTrue(result);
    }

    @Test
    void testResolveAnalysis_ReuseFromRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        MediaFile mediaFile = createMediaFile(MEDIA_ID);
        MediaFile owner = createMediaFile(OWNER_ID);
        owner.setAiStatus(AiStatus.SUCCESS.name());
        owner.setAiSummary("归属总结");
        owner.setTranscriptText("归属转写");
        owner.setTranscriptStatus(AiStatus.SUCCESS.name());

        when(valueOps.get(AnalysisTaskKeys.completedOwner(CONTENT_HASH)))
                .thenReturn(String.valueOf(OWNER_ID));
        when(mediaFileMapper.selectById(OWNER_ID)).thenReturn(owner);

        boolean result = gate.resolveAnalysis(mediaFile, CONTENT_HASH);

        assertTrue(result);
        assertEquals("归属总结", mediaFile.getAiSummary());
        assertEquals(AiStatus.SUCCESS.name(), mediaFile.getAiStatus());
        verify(mediaFileMapper).updateById(eq(mediaFile));
        verify(valueOps).set(eq(AnalysisTaskKeys.completedOwner(CONTENT_HASH)),
                eq(String.valueOf(OWNER_ID)), any());
    }

    @Test
    void testResolveAnalysis_ReuseFromDB() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        MediaFile mediaFile = createMediaFile(MEDIA_ID);
        mediaFile.setFileMd5(CONTENT_HASH);  // 设置真实 MD5
        MediaFile owner = createMediaFile(OWNER_ID);
        owner.setAiStatus(AiStatus.SUCCESS.name());
        owner.setAiSummary("DB 归属总结");
        owner.setTranscriptText("DB 归属转写");
        owner.setTranscriptStatus(AiStatus.SUCCESS.name());

        when(valueOps.get(anyString())).thenReturn(null);
        when(mediaFileMapper.selectCompletedAnalysisByMd5(CONTENT_HASH, MEDIA_ID)).thenReturn(owner);

        boolean result = gate.resolveAnalysis(mediaFile, CONTENT_HASH);

        assertTrue(result);
        assertEquals("DB 归属总结", mediaFile.getAiSummary());
        verify(mediaFileMapper).updateById(eq(mediaFile));
        verify(valueOps).set(eq(AnalysisTaskKeys.completedOwner(CONTENT_HASH)),
                eq(String.valueOf(OWNER_ID)), any());
    }

    @Test
    void testResolveAnalysis_NoReuse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        MediaFile mediaFile = createMediaFile(MEDIA_ID);
        mediaFile.setFileMd5(CONTENT_HASH);

        when(valueOps.get(anyString())).thenReturn(null);
        when(mediaFileMapper.selectCompletedAnalysisByMd5(CONTENT_HASH, MEDIA_ID)).thenReturn(null);

        boolean result = gate.resolveAnalysis(mediaFile, CONTENT_HASH);

        assertFalse(result);
        verify(mediaFileMapper, never()).updateById(any(MediaFile.class));
    }

    // ==================== 转写结果复用测试 ====================

    @Test
    void testResolveTranscript_Idempotent() {
        MediaFile mediaFile = createMediaFile(MEDIA_ID);
        mediaFile.setTranscriptStatus(AiStatus.SUCCESS.name());
        mediaFile.setTranscriptText("已有转写");

        String result = gate.resolveTranscript(mediaFile, CONTENT_HASH);

        assertEquals("已有转写", result);
    }

    @Test
    void testResolveTranscript_ReuseFromRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        MediaFile mediaFile = createMediaFile(MEDIA_ID);
        MediaFile owner = createMediaFile(OWNER_ID);
        owner.setTranscriptStatus(AiStatus.SUCCESS.name());
        owner.setTranscriptText("归属转写");

        when(valueOps.get(AnalysisTaskKeys.contextOwner(CONTENT_HASH)))
                .thenReturn(String.valueOf(OWNER_ID));
        when(mediaFileMapper.selectById(OWNER_ID)).thenReturn(owner);

        String result = gate.resolveTranscript(mediaFile, CONTENT_HASH);

        assertEquals("归属转写", result);
        assertEquals(AiStatus.SUCCESS.name(), mediaFile.getTranscriptStatus());
        verify(mediaFileMapper).updateById(eq(mediaFile));
    }

    @Test
    void testResolveTranscript_ReuseFromDB() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        MediaFile mediaFile = createMediaFile(MEDIA_ID);
        mediaFile.setFileMd5(CONTENT_HASH);  // 设置真实 MD5
        MediaFile owner = createMediaFile(OWNER_ID);
        owner.setTranscriptStatus(AiStatus.SUCCESS.name());
        owner.setTranscriptText("DB 归属转写");

        when(valueOps.get(anyString())).thenReturn(null);
        when(mediaFileMapper.selectCompletedTranscriptByMd5(CONTENT_HASH, MEDIA_ID)).thenReturn(owner);

        String result = gate.resolveTranscript(mediaFile, CONTENT_HASH);

        assertEquals("DB 归属转写", result);
        verify(mediaFileMapper).updateById(eq(mediaFile));
        verify(valueOps).set(eq(AnalysisTaskKeys.contextOwner(CONTENT_HASH)),
                eq(String.valueOf(OWNER_ID)), any());
    }

    @Test
    void testResolveTranscript_NoReuse() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        MediaFile mediaFile = createMediaFile(MEDIA_ID);
        mediaFile.setFileMd5(CONTENT_HASH);

        when(valueOps.get(anyString())).thenReturn(null);
        when(mediaFileMapper.selectCompletedTranscriptByMd5(CONTENT_HASH, MEDIA_ID)).thenReturn(null);

        String result = gate.resolveTranscript(mediaFile, CONTENT_HASH);

        assertNull(result);
        verify(mediaFileMapper, never()).updateById(any(MediaFile.class));
    }

    private MediaFile createMediaFile(Long id) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setAiStatus(AiStatus.NONE.name());
        file.setTranscriptStatus(AiStatus.NONE.name());
        file.setFileMd5("default-hash");  // 默认设置为非真实 MD5
        return file;
    }
}
