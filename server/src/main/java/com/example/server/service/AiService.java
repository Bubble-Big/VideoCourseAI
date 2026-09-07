package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.common.AiFailStage;
import com.example.server.common.AiStatus;
import com.example.server.common.GateOutcome;
import com.example.server.entity.MediaFile;
import com.example.server.exception.AiAnalysisException;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.strategy.AiAnalysisStrategy;
import com.example.server.utils.AnalysisTaskKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

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
    private final MediaService mediaService;
    private final FailedAnalysisTaskService failedTaskService;
    private final ContentTaskGate contentTaskGate;

    public AiService(MediaFileMapper mediaFileMapper,
                     @Qualifier("defaultAiStrategy") AiAnalysisStrategy aiAnalysisStrategy,
                     StringRedisTemplate redisTemplate,
                     MediaService mediaService,
                     FailedAnalysisTaskService failedTaskService,
                     ContentTaskGate contentTaskGate) {
        this.mediaFileMapper = mediaFileMapper;
        this.aiAnalysisStrategy = aiAnalysisStrategy;
        this.redisTemplate = redisTemplate;
        this.mediaService = mediaService;
        this.failedTaskService = failedTaskService;
        this.contentTaskGate = contentTaskGate;
    }

    /**
     * AI 分析（@Async 异步执行）：落库保证前端可见 + 异常内部消化。
     * <p>成功写 SUCCESS；永久失败落 FAILED + 台账；瞬时失败保持 PROCESSING + 刷新时间戳，
     * 由 {@code AnalysisCompensationScheduler} 定时补偿重试（不再上抛给 MQ 重投）。</p>
     *
     * @return CompletableFuture 包装的 GateOutcome，用于补偿调度器判断是否真正执行
     */
    @Async("aiTaskExecutor")
    public CompletableFuture<GateOutcome> asyncAnalyze(Long mediaId) {
        String contentHash = mediaService.contentHash(mediaId);
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
            mediaFileMapper.update(null, new LambdaUpdateWrapper<MediaFile>()
                .eq(MediaFile::getId, mediaFile.getId())
                .set(MediaFile::getAiStatus, AiStatus.PROCESSING.name())
                .set(MediaFile::getAiProcessAt, LocalDateTime.now()));

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
                    mediaFileMapper.update(null, new LambdaUpdateWrapper<MediaFile>()
                        .eq(MediaFile::getId, mediaFile.getId())
                        .set(MediaFile::getAiSummary, NO_SPEECH_SUMMARY)
                        .set(MediaFile::getAiStatus, AiStatus.SUCCESS.name()));
                    contentTaskGate.rememberAnalysis(contentHash, mediaFile.getId());
                    evictCache(mediaFile);
                    log.info("视频无语音内容，跳过 LLM, mediaId={}", mediaId);
                    return GateOutcome.PROCEED;
                }

                // 2. 智能总结
                String summary = aiAnalysisStrategy.generateSummaryFromText(text);
                mediaFile.setAiSummary(summary);
                mediaFile.setAiStatus(AiStatus.SUCCESS.name());
                mediaFileMapper.update(null, new LambdaUpdateWrapper<MediaFile>()
                    .eq(MediaFile::getId, mediaFile.getId())
                    .set(MediaFile::getAiSummary, summary)
                    .set(MediaFile::getAiStatus, AiStatus.SUCCESS.name()));
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
        return CompletableFuture.completedFuture(outcome);
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
            mediaFileMapper.update(null, new LambdaUpdateWrapper<MediaFile>()
                .eq(MediaFile::getId, mediaFile.getId())
                .set(MediaFile::getAiStatus, AiStatus.PROCESSING.name())
                .set(MediaFile::getAiProcessAt, LocalDateTime.now()));
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
                mediaFileMapper.update(null, new LambdaUpdateWrapper<MediaFile>()
                    .eq(MediaFile::getId, mediaFile.getId())
                    .set(MediaFile::getTranscriptStatus, AiStatus.NONE.name()));
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
            mediaFileMapper.update(null, new LambdaUpdateWrapper<MediaFile>()
                .eq(MediaFile::getId, mediaFile.getId())
                .set(MediaFile::getTranscriptStatus, AiStatus.FAILED.name())
                .set(MediaFile::getTranscriptText, null));
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
            mediaFileMapper.update(null, new LambdaUpdateWrapper<MediaFile>()
                .eq(MediaFile::getId, mediaFile.getId())
                .set(MediaFile::getTranscriptText, text)
                .set(MediaFile::getTranscriptStatus, AiStatus.SUCCESS.name()));
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
     * 失败落库：写 FAILED + 受控文案 + 同步 transcriptStatus + 删缓存。
     * <p>受控文案不拼接异常 message，避免底层 errBody 泄漏到前端。</p>
     */
    private void markFailed(MediaFile mediaFile, Exception e) {
        mediaFile.setAiStatus(AiStatus.FAILED.name());
        mediaFile.setAiSummary(null); // 失败不塞文案，由前端按状态渲染
        // 若转写阶段尚未成功（即失败发生在 transcribe），同步置 FAILED，避免与 aiStatus 不一致
        LambdaUpdateWrapper<MediaFile> wrapper = new LambdaUpdateWrapper<MediaFile>()
            .eq(MediaFile::getId, mediaFile.getId())
            .set(MediaFile::getAiStatus, AiStatus.FAILED.name())
            .set(MediaFile::getAiSummary, null);
        if (!AiStatus.SUCCESS.name().equals(mediaFile.getTranscriptStatus())) {
            mediaFile.setTranscriptStatus(AiStatus.FAILED.name());
            wrapper.set(MediaFile::getTranscriptStatus, AiStatus.FAILED.name());
        }
        mediaFileMapper.update(null, wrapper);
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
