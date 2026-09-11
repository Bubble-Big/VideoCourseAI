package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.common.AiStatus;
import com.example.server.common.GateOutcome;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.AnalysisTaskKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 内容级串行原语收敛服务
 * <p>
 * 统一「同内容同时只处理一次」的核心语义，收敛幂等键、分析锁、转写锁、归属复用逻辑。
 * 强制不变量：<strong>「跳过 ⇒ 复用或回滚」——任何非 PROCEED 路径都必须显式复用他人结果或回滚到可重试态。</strong>
 * </p>
 *
 * <h3>核心方法</h3>
 * <ul>
 * <li>{@link #inAnalysisLock} - 在分析锁内执行回调</li>
 * <li>{@link #inTranscribeLock} - 在转写锁内执行回调</li>
 * <li>{@link #resolveAnalysis} - 查询并复用分析结果</li>
 * <li>{@link #resolveTranscript} - 查询并复用转写结果</li>
 * <li>{@link #rememberAnalysis} - 登记分析归属</li>
 * <li>{@link #rememberTranscript} - 登记转写归属</li>
 * <li>{@link #tryMarkSubmitting} - 提交侧短窗口幂等标记</li>
 * <li>{@link #rollbackSubmitting} - 回滚提交标记</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentTaskGate {

    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final MediaFileMapper mediaFileMapper;
    private final TaskEventService taskEventService;

    @Value("${ai.analysis-lock-wait-seconds:600}")
    private int analysisLockWaitSeconds;

    @Value("${ai.context-lock-wait-seconds:600}")
    private int contextLockWaitSeconds;

    /**
     * 提交侧短窗口幂等标记 TTL（30秒）
     */
    private static final Duration SUBMIT_ACTIVE_TTL = Duration.ofSeconds(30);

    /**
     * 归属缓存 TTL（7天）
     */
    private static final Duration OWNER_CACHE_TTL = Duration.ofDays(7);

    // ==================== 提交侧幂等键 ====================

    /**
     * 尝试标记提交中状态（短窗口幂等键）
     * <p>
     * 用于提交侧防止并发重复投递，30秒 TTL。
     * 抢不到说明另一个并发请求正在提交，应返回成功让前端轮询。
     * </p>
     *
     * @param contentHash 内容指纹
     * @param mediaId     当前 mediaId
     * @return true=成功标记，false=已有并发提交
     */
    public boolean tryMarkSubmitting(String contentHash, Long mediaId) {
        String activeKey = AnalysisTaskKeys.active(contentHash);
        Boolean accepted = redisTemplate.opsForValue()
                .setIfAbsent(activeKey, String.valueOf(mediaId), SUBMIT_ACTIVE_TTL);
        return Boolean.TRUE.equals(accepted);
    }

    /**
     * 回滚提交标记
     * <p>
     * 提交失败时（如限流拒绝、MQ 投递失败）调用，删除幂等键允许重试。
     * </p>
     *
     * @param contentHash 内容指纹
     */
    public void rollbackSubmitting(String contentHash) {
        String activeKey = AnalysisTaskKeys.active(contentHash);
        redisTemplate.delete(activeKey);
    }

    // ==================== 内容级分析锁 ====================

    /**
     * 在分析锁内执行回调
     * <p>
     * 抢不到锁则阻塞等待（最多 {@code analysisLockWaitSeconds}），期望复用首个持锁任务的结果。
     * </p>
     *
     * @param contentHash 内容指纹
     * @param action      锁内回调（返回 {@link GateOutcome}）
     * @return PROCEED=持锁执行完毕 | REUSE=复用 | DEFER=超时/中断
     */
    public GateOutcome inAnalysisLock(String contentHash, Supplier<GateOutcome> action) {
        RLock lock = redissonClient.getLock(AnalysisTaskKeys.analysisLock(contentHash));
        boolean locked;
        try {
            locked = lock.tryLock(analysisLockWaitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("等待分析锁被中断，跳过 contentHash={}", contentHash);
            return GateOutcome.DEFER;
        }
        if (!locked) {
            log.info("等待分析锁超时，跳过 contentHash={}", contentHash);
            return GateOutcome.DEFER;
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // ==================== 内容级转写锁 ====================

    /**
     * 在转写锁内执行回调
     * <p>
     * 抢不到锁则阻塞等待（最多 {@code contextLockWaitSeconds}），期望复用首个持锁任务的结果。
     * </p>
     *
     * @param contentHash 内容指纹
     * @param action      锁内回调（返回 {@link GateOutcome}）
     * @return PROCEED=持锁执行完毕 | REUSE=复用 | DEFER=超时/中断
     */
    public GateOutcome inTranscribeLock(String contentHash, Supplier<GateOutcome> action) {
        RLock lock = redissonClient.getLock(AnalysisTaskKeys.contextLock(contentHash));
        boolean locked;
        try {
            locked = lock.tryLock(contextLockWaitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待转写锁被中断，跳过 contentHash={}", contentHash);
            return GateOutcome.DEFER;
        }
        if (!locked) {
            log.info("等待转写锁超时，跳过 contentHash={}", contentHash);
            return GateOutcome.DEFER;
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // ==================== 分析结果复用 ====================

    /**
     * 查询并复用分析结果
     * <p>
     * 查询逻辑：本 mediaId 已有成功结果（幂等）→ Redis 归属缓存 → DB 按 file_md5 反查。
     * 命中后回填结果到当前记录并更新归属缓存。
     * </p>
     *
     * @param mediaFile   当前记录
     * @param contentHash 内容指纹
     * @return true=已复用，false=无可复用结果
     */
    public boolean resolveAnalysis(MediaFile mediaFile, String contentHash) {
        // 本 mediaId 已有成功结果 → 幂等直接返回
        if (isSuccessAnalysis(mediaFile)) {
            return true;
        }
        // 内容级归属可复用：换 mediaId 重复上传的场景
        Long ownerMediaId = analysisResultOwner(contentHash);
        MediaFile owner = null;
        if (ownerMediaId != null && !ownerMediaId.equals(mediaFile.getId())) {
            owner = mediaFileMapper.selectById(ownerMediaId);
            if (!isSuccessAnalysis(owner)) {
                redisTemplate.delete(AnalysisTaskKeys.completedOwner(contentHash)); // 归属失效，清掉
                owner = null;
            }
        }
        // Redis 归属未命中/失效 → 回退 DB 按 file_md5 反查（V5 索引优化）
        if (owner == null && AnalysisTaskKeys.isRealMd5(contentHash)) {
            owner = mediaFileMapper.selectCompletedAnalysisByMd5(contentHash, mediaFile.getId());
        }
        if (owner != null) {
            mediaFile.setAiSummary(owner.getAiSummary());
            mediaFile.setAiStatus(AiStatus.SUCCESS.name());
            // 转写文本一并复用（owner 分析成功必有转写）
            LambdaUpdateWrapper<MediaFile> wrapper = new LambdaUpdateWrapper<MediaFile>()
                .eq(MediaFile::getId, mediaFile.getId())
                .set(MediaFile::getAiSummary, owner.getAiSummary())
                .set(MediaFile::getAiStatus, AiStatus.SUCCESS.name());
            if (owner.getTranscriptText() != null && !owner.getTranscriptText().isBlank()) {
                mediaFile.setTranscriptText(owner.getTranscriptText());
                mediaFile.setTranscriptStatus(AiStatus.SUCCESS.name());
                wrapper.set(MediaFile::getTranscriptText, owner.getTranscriptText())
                       .set(MediaFile::getTranscriptStatus, AiStatus.SUCCESS.name());
            }
            mediaFileMapper.update(null, wrapper);
            rememberAnalysis(contentHash, owner.getId()); // 回填归属缓存

            // SSE 推送：复用结果 SUCCESS
            taskEventService.publishAnalysis(mediaFile.getId(), AiStatus.SUCCESS.name(), owner.getAiSummary(), null);
            if (owner.getTranscriptText() != null && !owner.getTranscriptText().isBlank()) {
                taskEventService.publishTranscription(mediaFile.getId(), AiStatus.SUCCESS.name(), owner.getTranscriptText(), null);
            }

            return true;
        }
        return false;
    }

    /**
     * 登记分析归属
     * <p>
     * 分析落库后调用，写入 Redis 缓存（7 天 TTL）。
     * </p>
     *
     * @param contentHash 内容指纹
     * @param mediaId     归属 mediaId
     */
    public void rememberAnalysis(String contentHash, Long mediaId) {
        redisTemplate.opsForValue().set(
                AnalysisTaskKeys.completedOwner(contentHash),
                String.valueOf(mediaId),
                OWNER_CACHE_TTL);
    }

    /**
     * 结果归属读取：返回已完成该内容分析的 mediaId，无则 null。
     */
    private Long analysisResultOwner(String contentHash) {
        String value = redisTemplate.opsForValue().get(AnalysisTaskKeys.completedOwner(contentHash));
        return value == null ? null : Long.valueOf(value);
    }

    /**
     * 分析是否真正可用：状态为 SUCCESS 且 summary 非空。
     */
    private boolean isSuccessAnalysis(MediaFile mediaFile) {
        return mediaFile != null
                && AiStatus.SUCCESS.name().equals(mediaFile.getAiStatus())
                && mediaFile.getAiSummary() != null
                && !mediaFile.getAiSummary().isBlank();
    }

    // ==================== 转写结果复用 ====================

    /**
     * 查询并复用转写结果
     * <p>
     * 查询逻辑：本 mediaId 已有转写 → Redis 归属缓存 → DB 按 file_md5 反查。
     * 命中后回填结果到当前记录并更新归属缓存。
     * </p>
     *
     * @param mediaFile   当前记录
     * @param contentHash 内容指纹
     * @return 转写文本；null 表示无可复用结果
     */
    public String resolveTranscript(MediaFile mediaFile, String contentHash) {
        // 本 mediaId 已有转写
        if (isSuccessTranscript(mediaFile)) {
            return mediaFile.getTranscriptText();
        }
        Long ownerMediaId = transcriptOwner(contentHash);
        MediaFile owner = null;
        if (ownerMediaId != null && !ownerMediaId.equals(mediaFile.getId())) {
            owner = mediaFileMapper.selectById(ownerMediaId);
            // 仅当归属真正失效才清除：记录被删 / 转写 FAILED / 文本为空
            if (owner == null
                    || AiStatus.FAILED.name().equals(owner.getTranscriptStatus())
                    || owner.getTranscriptText() == null
                    || owner.getTranscriptText().isBlank()) {
                redisTemplate.delete(AnalysisTaskKeys.contextOwner(contentHash));
                owner = null;
            }
        }
        // Redis 归属未命中/失效 → 回退 DB 按 file_md5 反查（V5 索引优化）
        if (owner == null && AnalysisTaskKeys.isRealMd5(contentHash)) {
            owner = mediaFileMapper.selectCompletedTranscriptByMd5(contentHash, mediaFile.getId());
        }
        if (owner != null) {
            mediaFile.setTranscriptText(owner.getTranscriptText());
            mediaFile.setTranscriptStatus(AiStatus.SUCCESS.name());
            mediaFileMapper.update(null, new LambdaUpdateWrapper<MediaFile>()
                .eq(MediaFile::getId, mediaFile.getId())
                .set(MediaFile::getTranscriptText, owner.getTranscriptText())
                .set(MediaFile::getTranscriptStatus, AiStatus.SUCCESS.name()));
            rememberTranscript(contentHash, owner.getId()); // 回填归属缓存

            // SSE 推送：复用转写结果 SUCCESS
            taskEventService.publishTranscription(mediaFile.getId(), AiStatus.SUCCESS.name(), owner.getTranscriptText(), null);

            return owner.getTranscriptText();
        }
        return null;
    }

    /**
     * 登记转写归属
     * <p>
     * 转写落库后调用，写入 Redis 缓存（7 天 TTL）。
     * </p>
     *
     * @param contentHash 内容指纹
     * @param mediaId     归属 mediaId
     */
    public void rememberTranscript(String contentHash, Long mediaId) {
        redisTemplate.opsForValue().set(
                AnalysisTaskKeys.contextOwner(contentHash),
                String.valueOf(mediaId),
                OWNER_CACHE_TTL);
    }

    /**
     * 归属读取：返回已完成该内容转写的 mediaId，无则 null。
     */
    private Long transcriptOwner(String contentHash) {
        String value = redisTemplate.opsForValue().get(AnalysisTaskKeys.contextOwner(contentHash));
        return value == null ? null : Long.valueOf(value);
    }

    /**
     * 转写是否真正可用：状态为 SUCCESS 且文本非空。
     */
    private boolean isSuccessTranscript(MediaFile mediaFile) {
        return mediaFile != null
                && AiStatus.SUCCESS.name().equals(mediaFile.getTranscriptStatus())
                && mediaFile.getTranscriptText() != null
                && !mediaFile.getTranscriptText().isBlank();
    }
}

