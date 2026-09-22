package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.common.AiStatus;
import com.example.server.common.GateOutcome;
import com.example.server.entity.MediaAiAnalysis;
import com.example.server.entity.MediaFile;
import com.example.server.entity.MediaTranscription;
import com.example.server.mapper.MediaAiAnalysisMapper;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.MediaTranscriptionMapper;
import com.example.server.utils.AnalysisTaskKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
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
    private final MediaAiAnalysisMapper aiAnalysisMapper;
    private final MediaTranscriptionMapper transcriptionMapper;
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
        Long mediaId = mediaFile.getId();

        // 查询当前的分析记录
        MediaAiAnalysis currentAnalysis = aiAnalysisMapper.selectOne(
            new LambdaQueryWrapper<MediaAiAnalysis>().eq(MediaAiAnalysis::getMediaId, mediaId)
        );

        // 本 mediaId 已有成功结果 → 幂等直接返回
        if (isSuccessAnalysis(currentAnalysis)) {
            return true;
        }

        // 内容级归属可复用：换 mediaId 重复上传的场景
        Long ownerMediaId = analysisResultOwner(contentHash);
        MediaAiAnalysis owner = null;
        if (ownerMediaId != null && !ownerMediaId.equals(mediaId)) {
            owner = aiAnalysisMapper.selectOne(
                new LambdaQueryWrapper<MediaAiAnalysis>().eq(MediaAiAnalysis::getMediaId, ownerMediaId)
            );
            if (!isSuccessAnalysis(owner)) {
                redisTemplate.delete(AnalysisTaskKeys.completedOwner(contentHash));
                owner = null;
            }
        }

        // Redis 归属未命中/失效 → 回退 DB 按 file_md5 反查（V5 索引优化）
        if (owner == null && AnalysisTaskKeys.isRealMd5(contentHash)) {
            owner = aiAnalysisMapper.selectCompletedAnalysisByMd5(contentHash, mediaId);
        }

        if (owner != null) {
            // 初始化或更新当前分析记录
            if (currentAnalysis == null) {
                currentAnalysis = new MediaAiAnalysis();
                currentAnalysis.setMediaId(mediaId);
                currentAnalysis.setSummary(owner.getSummary());
                currentAnalysis.setStatus(AiStatus.SUCCESS.name());
                currentAnalysis.setProcessAt(LocalDateTime.now());
                currentAnalysis.setAttempts(0);
                currentAnalysis.setCompensationAttempts(0);
                currentAnalysis.setRetryCount(0);
                aiAnalysisMapper.insert(currentAnalysis);
            } else {
                currentAnalysis.setSummary(owner.getSummary());
                currentAnalysis.setStatus(AiStatus.SUCCESS.name());
                currentAnalysis.setProcessAt(LocalDateTime.now());
                int updated = aiAnalysisMapper.updateById(currentAnalysis);
                if (updated == 0) {
                    // 乐观锁冲突：重新查询最新记录
                    MediaAiAnalysis latest = aiAnalysisMapper.selectOne(
                        new LambdaQueryWrapper<MediaAiAnalysis>().eq(MediaAiAnalysis::getMediaId, mediaId)
                    );
                    if (latest == null || AiStatus.SUCCESS.name().equals(latest.getStatus())) {
                        log.info("分析结果复用回填被跳过（记录已丢失或已为SUCCESS），mediaId={}", mediaId);
                        return false;
                    }
                    // 基于最新 version 重试一次
                    latest.setSummary(owner.getSummary());
                    latest.setStatus(AiStatus.SUCCESS.name());
                    latest.setProcessAt(LocalDateTime.now());
                    int retried = aiAnalysisMapper.updateById(latest);
                    if (retried == 0) {
                        log.warn("分析结果复用回填重试仍冲突，放弃本次操作, mediaId={}", mediaId);
                        return false;
                    }
                    currentAnalysis = latest;
                }
            }

            // 转写文本一并复用（owner 分析成功必有转写）
            MediaTranscription ownerTranscription = transcriptionMapper.selectOne(
                new LambdaQueryWrapper<MediaTranscription>().eq(MediaTranscription::getMediaId, owner.getMediaId())
            );
            if (ownerTranscription != null && ownerTranscription.getTranscriptText() != null
                    && !ownerTranscription.getTranscriptText().isBlank()) {
                MediaTranscription currentTranscription = transcriptionMapper.selectOne(
                    new LambdaQueryWrapper<MediaTranscription>().eq(MediaTranscription::getMediaId, mediaId)
                );
                if (currentTranscription == null) {
                    currentTranscription = new MediaTranscription();
                    currentTranscription.setMediaId(mediaId);
                    currentTranscription.setTranscriptText(ownerTranscription.getTranscriptText());
                    currentTranscription.setStatus(AiStatus.SUCCESS.name());
                    currentTranscription.setProcessAt(LocalDateTime.now());
                    currentTranscription.setAttempts(0);
                    currentTranscription.setCompensationAttempts(0);
                    currentTranscription.setRetryCount(0);
                    transcriptionMapper.insert(currentTranscription);
                } else {
                    currentTranscription.setTranscriptText(ownerTranscription.getTranscriptText());
                    currentTranscription.setStatus(AiStatus.SUCCESS.name());
                    currentTranscription.setProcessAt(LocalDateTime.now());
                    int updatedTrans = transcriptionMapper.updateById(currentTranscription);
                    if (updatedTrans == 0) {
                        // 乐观锁冲突：重新查询最新记录
                        MediaTranscription latestTrans = transcriptionMapper.selectOne(
                            new LambdaQueryWrapper<MediaTranscription>().eq(MediaTranscription::getMediaId, mediaId)
                        );
                        if (latestTrans == null || AiStatus.SUCCESS.name().equals(latestTrans.getStatus())) {
                            log.info("转写结果复用回填被跳过（记录已丢失或已为SUCCESS），mediaId={}", mediaId);
                            // 转写失败不影响分析结果已成功的事实，继续后续流程
                        } else {
                            // 基于最新 version 重试一次
                            latestTrans.setTranscriptText(ownerTranscription.getTranscriptText());
                            latestTrans.setStatus(AiStatus.SUCCESS.name());
                            latestTrans.setProcessAt(LocalDateTime.now());
                            int retriedTrans = transcriptionMapper.updateById(latestTrans);
                            if (retriedTrans == 0) {
                                log.warn("转写结果复用回填重试仍冲突，放弃本次操作, mediaId={}", mediaId);
                                // 转写失败不影响分析结果已成功的事实，继续后续流程
                            } else {
                                currentTranscription = latestTrans;
                            }
                        }
                    }
                }

                // SSE 推送：复用转写结果（仅在成功落库或已存在时推送）
                if (currentTranscription != null && AiStatus.SUCCESS.name().equals(currentTranscription.getStatus())) {
                    taskEventService.publishTranscription(mediaId, AiStatus.SUCCESS.name(),
                        ownerTranscription.getTranscriptText(), null);
                }
            }

            rememberAnalysis(contentHash, owner.getMediaId());

            // SSE 推送：复用结果 SUCCESS
            taskEventService.publishAnalysis(mediaId, AiStatus.SUCCESS.name(), owner.getSummary(), null);

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
    private boolean isSuccessAnalysis(MediaAiAnalysis aiAnalysis) {
        return aiAnalysis != null
                && AiStatus.SUCCESS.name().equals(aiAnalysis.getStatus())
                && aiAnalysis.getSummary() != null
                && !aiAnalysis.getSummary().isBlank();
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
        Long mediaId = mediaFile.getId();

        // 查询当前的转写记录
        MediaTranscription currentTranscription = transcriptionMapper.selectOne(
            new LambdaQueryWrapper<MediaTranscription>().eq(MediaTranscription::getMediaId, mediaId)
        );

        // 本 mediaId 已有转写
        if (isSuccessTranscript(currentTranscription)) {
            return currentTranscription.getTranscriptText();
        }

        Long ownerMediaId = transcriptOwner(contentHash);
        MediaTranscription owner = null;
        if (ownerMediaId != null && !ownerMediaId.equals(mediaId)) {
            owner = transcriptionMapper.selectOne(
                new LambdaQueryWrapper<MediaTranscription>().eq(MediaTranscription::getMediaId, ownerMediaId)
            );
            // 仅当归属真正失效才清除：记录被删 / 转写 FAILED / 文本为空
            if (!isSuccessTranscript(owner)) {
                redisTemplate.delete(AnalysisTaskKeys.contextOwner(contentHash));
                owner = null;
            }
        }

        // Redis 归属未命中/失效 → 回退 DB 按 file_md5 反查（V5 索引优化）
        if (owner == null && AnalysisTaskKeys.isRealMd5(contentHash)) {
            owner = transcriptionMapper.selectCompletedTranscriptByMd5(contentHash, mediaId);
        }

        if (owner != null) {
            // 初始化或更新当前转写记录
            if (currentTranscription == null) {
                currentTranscription = new MediaTranscription();
                currentTranscription.setMediaId(mediaId);
                currentTranscription.setTranscriptText(owner.getTranscriptText());
                currentTranscription.setStatus(AiStatus.SUCCESS.name());
                currentTranscription.setProcessAt(LocalDateTime.now());
                currentTranscription.setAttempts(0);
                currentTranscription.setCompensationAttempts(0);
                currentTranscription.setRetryCount(0);
                transcriptionMapper.insert(currentTranscription);
            } else {
                currentTranscription.setTranscriptText(owner.getTranscriptText());
                currentTranscription.setStatus(AiStatus.SUCCESS.name());
                currentTranscription.setProcessAt(LocalDateTime.now());
                int updated = transcriptionMapper.updateById(currentTranscription);
                if (updated == 0) {
                    // 乐观锁冲突：重新查询最新记录
                    MediaTranscription latest = transcriptionMapper.selectOne(
                        new LambdaQueryWrapper<MediaTranscription>().eq(MediaTranscription::getMediaId, mediaId)
                    );
                    if (latest == null || AiStatus.SUCCESS.name().equals(latest.getStatus())) {
                        log.info("转写结果复用回填被跳过（记录已丢失或已为SUCCESS），mediaId={}", mediaId);
                        // 已成功或丢失，直接返回 owner 的文本
                        rememberTranscript(contentHash, owner.getMediaId());
                        taskEventService.publishTranscription(mediaId, AiStatus.SUCCESS.name(),
                            owner.getTranscriptText(), null);
                        return owner.getTranscriptText();
                    }
                    // 基于最新 version 重试一次
                    latest.setTranscriptText(owner.getTranscriptText());
                    latest.setStatus(AiStatus.SUCCESS.name());
                    latest.setProcessAt(LocalDateTime.now());
                    int retried = transcriptionMapper.updateById(latest);
                    if (retried == 0) {
                        log.warn("转写结果复用回填重试仍冲突，放弃本次操作, mediaId={}", mediaId);
                        return null;
                    }
                    currentTranscription = latest;
                }
            }

            rememberTranscript(contentHash, owner.getMediaId());

            // SSE 推送：复用转写结果 SUCCESS
            taskEventService.publishTranscription(mediaId, AiStatus.SUCCESS.name(),
                owner.getTranscriptText(), null);

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
    private boolean isSuccessTranscript(MediaTranscription transcription) {
        return transcription != null
                && AiStatus.SUCCESS.name().equals(transcription.getStatus())
                && transcription.getTranscriptText() != null
                && !transcription.getTranscriptText().isBlank();
    }
}

