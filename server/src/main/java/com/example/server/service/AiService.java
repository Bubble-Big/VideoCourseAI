package com.example.server.service;

import com.example.server.common.AiFailStage;
import com.example.server.common.AiStatus;
import com.example.server.common.GateOutcome;
import com.example.server.entity.MediaFile;
import com.example.server.exception.AiAnalysisException;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.strategy.AiAnalysisStrategy;
import com.example.server.utils.AnalysisTaskKeys;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    /** 无语音内容时的转写受控文案（前端文字提取直接展示）。 */
    private static final String NO_SPEECH_TRANSCRIPT = "视频未提取到有效语音信息";
    /** 无语音内容时的分析受控文案（前端 AI 分析直接展示）。 */
    private static final String NO_SPEECH_SUMMARY = "视频未提取到有效信息，无法分析";

    private final MediaFileMapper mediaFileMapper;
    private final AiAnalysisStrategy aiAnalysisStrategy;
    // 【关键】必须注入 Redis 工具！
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final MediaService mediaService;
    private final FailedAnalysisTaskService failedTaskService;
    private final ContentTaskGate contentTaskGate;
    /** 分析锁等待时长（秒）：抢不到锁时阻塞等待首个持锁任务完成以便复用结果，对齐 ASR readTimeout，非无限等待。 */
    private final long analysisLockWaitSeconds;
    /** 转写锁等待时长（秒）：抢不到转写锁时阻塞等待他人转写完成以便复用结果，对齐 ASR readTimeout。 */
    private final long contextLockWaitSeconds;

    @Value("${content.gate.enabled:true}")
    private boolean gateEnabled;

    public AiService(MediaFileMapper mediaFileMapper,
                     @Qualifier("defaultAiStrategy") AiAnalysisStrategy aiAnalysisStrategy,
                     StringRedisTemplate redisTemplate,
                     RedissonClient redissonClient,
                     MediaService mediaService,
                     FailedAnalysisTaskService failedTaskService,
                     ContentTaskGate contentTaskGate,
                     @Value("${ai.analysis-lock-wait-seconds:600}") long analysisLockWaitSeconds,
                     @Value("${ai.transcribe-lock-wait-seconds:600}") long contextLockWaitSeconds) {
        this.mediaFileMapper = mediaFileMapper;
        this.aiAnalysisStrategy = aiAnalysisStrategy;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.mediaService = mediaService;
        this.failedTaskService = failedTaskService;
        this.contentTaskGate = contentTaskGate;
        this.analysisLockWaitSeconds = analysisLockWaitSeconds;
        this.contextLockWaitSeconds = contextLockWaitSeconds;
    }

    /**
     * AI 分析（@Async 异步执行）：落库保证前端可见 + 异常内部消化。
     * <p>成功写 SUCCESS；永久失败落 FAILED + 台账；瞬时失败保持 PROCESSING + 刷新时间戳，
     * 由 {@code AnalysisCompensationScheduler} 定时补偿重试（不再上抛给 MQ 重投）。</p>
     */
    @Async("aiTaskExecutor")
    public void asyncAnalyze(Long mediaId) {
        String contentHash = mediaService.contentHash(mediaId);

        if (gateEnabled) {
            asyncAnalyzeWithGate(mediaId, contentHash);
        } else {
            asyncAnalyzeLegacy(mediaId, contentHash);
        }
    }

    /**
     * 新版分析逻辑：使用 ContentTaskGate
     */
    private void asyncAnalyzeWithGate(Long mediaId, String contentHash) {
        GateOutcome outcome = contentTaskGate.inAnalysisLock(contentHash, () -> {
            MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
            if (mediaFile == null) {
                throw new AiAnalysisException("文件不存在: " + mediaId, false, AiFailStage.FILE);
            }

            // 结果复用：查询并回填已有结果
            if (contentTaskGate.resolveAnalysis(mediaFile, contentHash)) {
                evictCache(mediaFile);
                log.info("AI 分析结果复用, mediaId={} contentHash={}", mediaId, contentHash);
                return GateOutcome.REUSE;
            }

            // 进入处理态：复用未命中才置 PROCESSING + 刷新时间戳
            mediaFile.setAiStatus(AiStatus.PROCESSING.name());
            mediaFile.setAiProcessAt(LocalDateTime.now());
            mediaFileMapper.updateById(mediaFile);

            try {
                // 1. 语音转文字：内容级锁 + 归属复用
                String text = transcribeWithReuse(mediaFile, contentHash);
                if (text == null) {
                    throw new AiAnalysisException("等待转写锁超时，稍后重试", true, AiFailStage.LOCK);
                }
                mediaFile.setTranscriptText(text);
                mediaFile.setTranscriptStatus(AiStatus.SUCCESS.name());

                if (NO_SPEECH_TRANSCRIPT.equals(text)) {
                    // 无语音内容：跳过 LLM，直接落受控总结文案
                    mediaFile.setAiSummary(NO_SPEECH_SUMMARY);
                    mediaFile.setAiStatus(AiStatus.SUCCESS.name());
                    mediaFileMapper.updateById(mediaFile);
                    contentTaskGate.rememberAnalysis(contentHash, mediaFile.getId());
                    evictCache(mediaFile);
                    log.info("视频无语音内容，跳过 LLM, mediaId={}", mediaId);
                    return GateOutcome.PROCEED;
                }

                // 2. 智能总结
                String summary = aiAnalysisStrategy.generateSummaryFromText(text);
                mediaFile.setAiSummary(summary);
                mediaFile.setAiStatus(AiStatus.SUCCESS.name());
                mediaFileMapper.updateById(mediaFile);
                contentTaskGate.rememberAnalysis(contentHash, mediaFile.getId());
                evictCache(mediaFile);
                log.info("AI 分析完成, mediaId={}", mediaId);
                return GateOutcome.PROCEED;

            } catch (Exception e) {
                handleAnalysisException(mediaFile, mediaId, e);
                return GateOutcome.PROCEED;  // 异常已处理，视为完成
            }
        });

        // DEFER：让位，保持 PENDING/PROCESSING 交补偿
        if (outcome == GateOutcome.DEFER) {
            log.info("分析锁让位, mediaId={} contentHash={}", mediaId, contentHash);
        }
    }

    /**
     * 旧版分析逻辑（向后兼容，待稳定后删除）
     */
    private void asyncAnalyzeLegacy(Long mediaId, String contentHash) {
        MediaFile mediaFile = null;
        RLock lock = redissonClient.getLock(AnalysisTaskKeys.analysisLock(contentHash));
        boolean locked;
        try {
            locked = lock.tryLock(analysisLockWaitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("等待分析锁被中断，跳过 mediaId={} contentHash={}", mediaId, contentHash);
            return;
        }
        if (!locked) {
            log.info("等待分析锁超时，跳过 mediaId={} contentHash={}", mediaId, contentHash);
            return;
        }
        try {
            mediaFile = mediaFileMapper.selectById(mediaId);
            if (mediaFile == null) {
                throw new AiAnalysisException("文件不存在: " + mediaId, false, AiFailStage.FILE);
            }
            log.info("开始 AI 分析任务, mediaId={}", mediaId);

            if (resolveAnalysisResult(mediaFile, contentHash)) {
                evictCache(mediaFile);
                log.info("AI 分析结果复用, mediaId={} contentHash={}", mediaId, contentHash);
                return;
            }

            mediaFile.setAiStatus(AiStatus.PROCESSING.name());
            mediaFile.setAiProcessAt(LocalDateTime.now());
            mediaFileMapper.updateById(mediaFile);

            String text = transcribeWithReuse(mediaFile, contentHash);
            if (text == null) {
                throw new AiAnalysisException("等待转写锁超时，稍后重试", true, AiFailStage.LOCK);
            }
            mediaFile.setTranscriptText(text);
            mediaFile.setTranscriptStatus(AiStatus.SUCCESS.name());

            if (NO_SPEECH_TRANSCRIPT.equals(text)) {
                mediaFile.setAiSummary(NO_SPEECH_SUMMARY);
                mediaFile.setAiStatus(AiStatus.SUCCESS.name());
                mediaFileMapper.updateById(mediaFile);
                rememberAnalysisResult(contentHash, mediaFile.getId());
                evictCache(mediaFile);
                log.info("视频无语音内容，跳过 LLM, mediaId={}", mediaId);
                return;
            }

            String summary = aiAnalysisStrategy.generateSummaryFromText(text);
            mediaFile.setAiSummary(summary);
            mediaFile.setAiStatus(AiStatus.SUCCESS.name());

            mediaFileMapper.updateById(mediaFile);
            rememberAnalysisResult(contentHash, mediaFile.getId());
            evictCache(mediaFile);
            log.info("AI 分析完成, mediaId={}", mediaId);

        } catch (Exception e) {
            handleAnalysisException(mediaFile, mediaId, e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 统一异常处理：永久失败落 FAILED，瞬时失败保持 PROCESSING 交补偿
     */
    private void handleAnalysisException(MediaFile mediaFile, Long mediaId, Exception e) {
        if (e instanceof AiAnalysisException ae && !ae.isRetryable()) {
            if (mediaFile != null) {
                markFailed(mediaFile, e);
            }
            failedTaskService.record(mediaId, ae, attemptsOf(mediaFile));
            return;
        }
        if (mediaFile != null) {
            // 瞬时失败 / 未预期异常：保持 PROCESSING + 刷新时间戳，等定时补偿重试
            mediaFile.setAiStatus(AiStatus.PROCESSING.name());
            mediaFile.setAiProcessAt(LocalDateTime.now());
            mediaFileMapper.updateById(mediaFile);
        }
        if (e instanceof AiAnalysisException ae) {
            failedTaskService.record(mediaId, ae, attemptsOf(mediaFile));
        }
        log.warn("AI 分析瞬时失败，保持 PROCESSING 等待补偿重试, mediaId={}, err={}", mediaId, e.getMessage());
    }

    /**
     * 落失败（供补偿调度器 / DLQ 兜底调用）：绕过可重试判断，直接写 FAILED + 受控文案。
     */
    public void markFailedFinal(Long mediaId) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            return;
        }
        markFailed(mediaFile, new AiAnalysisException("重试耗尽，判定失败", false));
    }

    /**
     * 异步提取全文（@Async 一次性任务，无 MQ 消费层接收重试，失败只落库不上抛）。
     */
    @Async("aiTaskExecutor")
    public void asyncTranscribe(Long mediaId) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            log.warn("全文提取任务找不到文件记录, mediaId={}", mediaId);
            return;
        }
        log.info("开始全文提取任务, mediaId={}", mediaId);

        try {
            // 内容级锁 + 归属复用：同一内容只转写一次；抢不到锁则等待他人转写完成后复用（对齐 AI 分析）
            String contentHash = mediaService.contentHash(mediaId);
            String text = transcribeWithReuse(mediaFile, contentHash);
            if (text == null) {
                // 等待转写锁超时仍未复用：回滚到 NONE 允许重试，避免永久卡 PROCESSING
                mediaFile.setTranscriptStatus(AiStatus.NONE.name());
                mediaFileMapper.updateById(mediaFile);
                evictCache(mediaFile);
                log.info("等待转写锁超时，回滚待重试, mediaId={} contentHash={}", mediaId, contentHash);
                return;
            }
            // transcribeWithReuse 内部已落库 transcriptText / transcriptStatus 并登记归属
            evictCache(mediaFile);
            log.info("全文提取完成, mediaId={}", mediaId);

        } catch (Exception e) {
            log.error("全文提取失败, mediaId={}, err={}", mediaId, e.getMessage(), e);
            // 失败只置状态字段，不塞失败文案进内容字段（由前端按状态渲染）
            mediaFile.setTranscriptStatus(AiStatus.FAILED.name());
            mediaFile.setTranscriptText(null);
            mediaFileMapper.updateById(mediaFile);
            evictCache(mediaFile);
        }
    }

    // ==================== 内容级转写锁 + 归属复用 ====================

    /**
     * 统一转写入口：锁内「查归属 → 复用或转写 → 登记归属」。
     * <p>ASR 结果只取决于内容，与归属用户 / 分析目标无关，按 contentHash 复用转写文本，
     * 同一内容只真正转写一次。</p>
     *
     * @param mediaFile   目标记录
     * @param contentHash 内容指纹
     * @return 转写文本；null 表示等待转写锁超时且无归属可复用
     */
    private String transcribeWithReuse(MediaFile mediaFile, String contentHash) {
        return gateEnabled
                ? transcribeWithReuseGate(mediaFile, contentHash)
                : transcribeWithReuseLegacy(mediaFile, contentHash);
    }

    /**
     * 新版转写逻辑：使用 ContentTaskGate
     */
    private String transcribeWithReuseGate(MediaFile mediaFile, String contentHash) {
        String[] resultHolder = new String[1];
        GateOutcome outcome = contentTaskGate.inTranscribeLock(contentHash, () -> {
            // 锁内先查复用
            String reusable = contentTaskGate.resolveTranscript(mediaFile, contentHash);
            if (reusable != null) {
                resultHolder[0] = reusable;
                return GateOutcome.REUSE;
            }

            // 抢到锁且无归属：真正转写一次
            String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
            if (text == null || text.isBlank()) {
                text = NO_SPEECH_TRANSCRIPT;
            }
            mediaFile.setTranscriptText(text);
            mediaFile.setTranscriptStatus(AiStatus.SUCCESS.name());
            mediaFileMapper.updateById(mediaFile);
            contentTaskGate.rememberTranscript(contentHash, mediaFile.getId());
            resultHolder[0] = text;
            return GateOutcome.PROCEED;
        });

        // DEFER：让位，未抢到锁也没复用到结果
        if (outcome == GateOutcome.DEFER) {
            return null;
        }
        return resultHolder[0];
    }

    /**
     * 旧版转写逻辑（向后兼容，待稳定后删除）
     */
    private String transcribeWithReuseLegacy(MediaFile mediaFile, String contentHash) {
        RLock lock = redissonClient.getLock(AnalysisTaskKeys.contextLock(contentHash));
        boolean locked = false;
        try {
            locked = lock.tryLock(contextLockWaitSeconds, TimeUnit.SECONDS);
            // 无论是否抢到锁都先查归属：已有人完成则直接复用
            String reusable = resolveTranscript(mediaFile, contentHash);
            if (reusable != null) return reusable;
            if (!locked) return null; // 没抢到且没复用：别人在转写，跳过

            // 抢到锁且无归属：真正转写一次
            String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
            if (text == null || text.isBlank()) {
                text = NO_SPEECH_TRANSCRIPT;   // 无语音：算成功，落受控文案
            }
            mediaFile.setTranscriptText(text);
            mediaFile.setTranscriptStatus(AiStatus.SUCCESS.name());
            mediaFileMapper.updateById(mediaFile);
            rememberTranscriptOwner(contentHash, mediaFile.getId()); // 先落库再登记归属
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待转写锁被中断", e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    /**
     * 归属读取：返回已完成该内容转写的 mediaId，无则 null。
     */
    private Long transcriptOwner(String contentHash) {
        String value = redisTemplate.opsForValue().get(AnalysisTaskKeys.contextOwner(contentHash));
        return value == null ? null : Long.valueOf(value);
    }

    /**
     * 归属登记：转写落库后写入，7 天 TTL。
     */
    private void rememberTranscriptOwner(String contentHash, Long mediaId) {
        redisTemplate.opsForValue().set(
                AnalysisTaskKeys.contextOwner(contentHash), String.valueOf(mediaId), Duration.ofDays(7));
    }

    /**
     * 复用查询：本 mediaId 已有转写，或内容级归属可复用，返回文本；否则 null。
     */
    private String resolveTranscript(MediaFile mediaFile, String contentHash) {
        // 只有转写成功且文本非空才算可复用；FAILED 文案（如「❌ 提取失败」）不能当有效文本喂给 LLM
        if (isSuccessTranscript(mediaFile)) {
            return mediaFile.getTranscriptText();
        }
        Long ownerMediaId = transcriptOwner(contentHash);
        MediaFile owner = null;
        if (ownerMediaId != null && !ownerMediaId.equals(mediaFile.getId())) {
            owner = mediaFileMapper.selectById(ownerMediaId);
            // 仅当归属真正失效才清除：记录被删 / 转写 FAILED / 文本为空。
            // PROCESSING（重转写中）但旧文本仍在时保留归属，复用旧结果，避免误删 + 额外 ASR。
            if (owner == null
                    || AiStatus.FAILED.name().equals(owner.getTranscriptStatus())
                    || owner.getTranscriptText() == null
                    || owner.getTranscriptText().isBlank()) {
                redisTemplate.delete(AnalysisTaskKeys.contextOwner(contentHash)); // 归属失效，清掉
                owner = null;
            }
        }
        // Redis 归属未命中/失效 → 回退 DB 按 file_md5 反查（归属的权威数据源是 MySQL，Redis 只是 7 天缓存）
        if (owner == null && AnalysisTaskKeys.isRealMd5(contentHash)) {
            owner = mediaFileMapper.selectCompletedTranscriptByMd5(contentHash, mediaFile.getId());
        }
        if (owner != null) {
            mediaFile.setTranscriptText(owner.getTranscriptText());
            mediaFile.setTranscriptStatus(AiStatus.SUCCESS.name());
            mediaFileMapper.updateById(mediaFile);
            rememberTranscriptOwner(contentHash, owner.getId()); // 回填归属缓存，下次走 Redis 快速路径
            return owner.getTranscriptText();
        }
        return null;
    }

    /**
     * 转写是否真正可用：状态为 SUCCESS 且文本非空。
     * <p>失败记录会把受控文案（如「❌ 提取失败」）写进 transcriptText，
     * 不能只判非空就复用，否则会把错误提示当有效文本喂给总结 LLM。</p>
     */
    private boolean isSuccessTranscript(MediaFile mediaFile) {
        return mediaFile != null
                && AiStatus.SUCCESS.name().equals(mediaFile.getTranscriptStatus())
                && mediaFile.getTranscriptText() != null
                && !mediaFile.getTranscriptText().isBlank();
    }

    /**
     * 分析结果是否真正可用：状态为 SUCCESS 且 summary 非空。
     * <p>与 {@link #isSuccessTranscript} 同理，不能只判非空，否则会把失败受控文案当有效结果复用。</p>
     */
    private boolean isSuccessAnalysis(MediaFile mediaFile) {
        return mediaFile != null
                && AiStatus.SUCCESS.name().equals(mediaFile.getAiStatus())
                && mediaFile.getAiSummary() != null
                && !mediaFile.getAiSummary().isBlank();
    }

    /**
     * 结果复用查询：本 mediaId 已有成功结果（幂等），或内容级归属可复用（换 mediaId 重复上传），返回 true；否则 false。
     */
    private boolean resolveAnalysisResult(MediaFile mediaFile, String contentHash) {
        // 本 mediaId 已有成功结果 → 幂等直接返回（MQ 重投等）
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
        // Redis 归属未命中/失效 → 回退 DB 按 file_md5 反查（归属的权威数据源是 MySQL，Redis 只是 7 天缓存）
        if (owner == null && AnalysisTaskKeys.isRealMd5(contentHash)) {
            owner = mediaFileMapper.selectCompletedAnalysisByMd5(contentHash, mediaFile.getId());
        }
        if (owner != null) {
            mediaFile.setAiSummary(owner.getAiSummary());
            mediaFile.setAiStatus(AiStatus.SUCCESS.name());
            // 转写文本一并复用（owner 分析成功必有转写）
            if (owner.getTranscriptText() != null && !owner.getTranscriptText().isBlank()) {
                mediaFile.setTranscriptText(owner.getTranscriptText());
                mediaFile.setTranscriptStatus(AiStatus.SUCCESS.name());
            }
            mediaFileMapper.updateById(mediaFile);
            rememberAnalysisResult(contentHash, owner.getId()); // 回填归属缓存，下次走 Redis 快速路径
            return true;
        }
        return false;
    }

    /**
     * 结果归属读取：返回已完成该内容分析的 mediaId，无则 null。
     */
    private Long analysisResultOwner(String contentHash) {
        String value = redisTemplate.opsForValue().get(AnalysisTaskKeys.completedOwner(contentHash));
        return value == null ? null : Long.valueOf(value);
    }

    /**
     * 结果归属登记：分析落库后写入，7 天 TTL。
     */
    private void rememberAnalysisResult(String contentHash, Long mediaId) {
        redisTemplate.opsForValue().set(
                AnalysisTaskKeys.completedOwner(contentHash), String.valueOf(mediaId), Duration.ofDays(7));
    }

    /**
     * 失败落库：写 FAILED + 受控文案 + 同步 transcriptStatus + 删缓存。
     * <p>受控文案不拼接异常 message，避免底层 errBody 泄漏到前端。</p>
     */
    private void markFailed(MediaFile mediaFile, Exception e) {
        mediaFile.setAiStatus(AiStatus.FAILED.name());
        mediaFile.setAiSummary(null); // 失败不塞文案，由前端按状态渲染
        // 若转写阶段尚未成功（即失败发生在 transcribe），同步置 FAILED，避免与 aiStatus 不一致
        if (!AiStatus.SUCCESS.name().equals(mediaFile.getTranscriptStatus())) {
            mediaFile.setTranscriptStatus(AiStatus.FAILED.name());
        }
        mediaFileMapper.updateById(mediaFile);
        evictCache(mediaFile);
        log.error("AI 分析失败, mediaId={}, err={}", mediaFile.getId(), e.getMessage(), e);
    }

    /**
     * 台账 attempts 取值：直传补偿重试次数（首次失败未重试为 0），文件不存在时兜底 0。
     */
    private int attemptsOf(MediaFile mediaFile) {
        return mediaFile == null || mediaFile.getAiAttempts() == null ? 0 : mediaFile.getAiAttempts();
    }

    /**
     * 失效列表缓存，拼装 Key 规则与 MediaController.list 保持一致。
     */
    private void evictCache(MediaFile mediaFile) {
        String userIdStr = (mediaFile.getUserId() == null) ? "anon" : String.valueOf(mediaFile.getUserId());
        redisTemplate.delete("media:list:user:" + userIdStr);
    }
}
