package com.example.server.service;

import com.example.server.common.AiStatus;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    /** 等待别人转写完成的窗口（分析依赖转写结果做总结，可等待；独立转写不等待）。 */
    private static final long CONTEXT_LOCK_WAIT_SECONDS = 300;

    private final MediaFileMapper mediaFileMapper;
    private final AiAnalysisStrategy aiAnalysisStrategy;
    // 【关键】必须注入 Redis 工具！
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final MediaService mediaService;
    private final FailedAnalysisTaskService failedTaskService;

    public AiService(MediaFileMapper mediaFileMapper,
                     @Qualifier("defaultAiStrategy") AiAnalysisStrategy aiAnalysisStrategy,
                     StringRedisTemplate redisTemplate,
                     RedissonClient redissonClient,
                     MediaService mediaService,
                     FailedAnalysisTaskService failedTaskService) {
        this.mediaFileMapper = mediaFileMapper;
        this.aiAnalysisStrategy = aiAnalysisStrategy;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.mediaService = mediaService;
        this.failedTaskService = failedTaskService;
    }

    /**
     * AI 分析（@Async 异步执行）：落库保证前端可见 + 异常内部消化。
     * <p>成功写 SUCCESS；永久失败落 FAILED + 台账；瞬时失败保持 PROCESSING + 刷新时间戳，
     * 由 {@code AnalysisCompensationScheduler} 定时补偿重试（不再上抛给 MQ 重投）。</p>
     */
    @Async("aiTaskExecutor")
    public void asyncAnalyze(Long mediaId) {
        MediaFile mediaFile = null;
        // ① 内容级锁：从消费层移到这里（执行在异步线程，锁跟随执行线程）
        String contentHash = mediaService.contentHash(mediaId);
        RLock lock = redissonClient.getLock(AnalysisTaskKeys.analysisLock(contentHash));
        if (!lock.tryLock()) {
            log.info("分析任务已在执行，跳过 mediaId={} contentHash={}", mediaId, contentHash);
            return;   // 同一内容已在跑（并发触发 / 补偿重复），跳过
        }
        try {
            mediaFile = mediaFileMapper.selectById(mediaId);
            if (mediaFile == null) {
                throw new AiAnalysisException("文件不存在: " + mediaId, false);
            }
            log.info("开始 AI 分析任务, mediaId={}", mediaId);

            // ② 进入处理态：只置 PROCESSING + 刷新时间戳（ai_attempts 由补偿触发侧统一 +1，这里不计数）
            mediaFile.setAiStatus(AiStatus.PROCESSING.name());
            mediaFile.setAiProcessAt(LocalDateTime.now());
            mediaFileMapper.updateById(mediaFile);

            // 【结果复用】同一内容已分析完成 → 复制 summary 直接返回，不再烧 ASR + LLM
            if (resolveAnalysisResult(mediaFile, contentHash)) {
                evictCache(mediaFile);
                log.info("AI 分析结果复用, mediaId={} contentHash={}", mediaId, contentHash);
                return;
            }

            // 1. 语音转文字：内容级锁 + 归属复用（同一内容只真正转写一次）
            String text = transcribeWithReuse(mediaFile, contentHash, true);
            if (text == null) {
                throw new AiAnalysisException("等待转写锁超时，稍后重试", true);
            }
            mediaFile.setTranscriptText(text);
            mediaFile.setTranscriptStatus(AiStatus.SUCCESS.name());

            // 2. 智能总结：复用已转写文本，避免重复提取音频 + ASR
            String summary = aiAnalysisStrategy.generateSummaryFromText(text);
            mediaFile.setAiSummary(summary);
            mediaFile.setAiStatus(AiStatus.SUCCESS.name());

            mediaFileMapper.updateById(mediaFile);
            rememberAnalysisResult(contentHash, mediaFile.getId()); // 完成后登记结果归属
            evictCache(mediaFile);
            log.info("AI 分析完成, mediaId={}", mediaId);

        } catch (Exception e) {
            // @Async 隔离了异常传播，异常不再能抛回消费层，必须内部消化：
            // 永久失败落 FAILED + 台账；瞬时失败保持 PROCESSING + 刷新时间戳，交补偿重试
            if (e instanceof AiAnalysisException ae && !ae.isRetryable()) {
                if (mediaFile != null) {
                    markFailed(mediaFile, e);   // 永久失败，落 FAILED（文件不存在时 mediaFile 为 null，无行可落）
                }
                failedTaskService.record(mediaId, ae);
                return;
            }
            if (mediaFile != null) {
                // 瞬时失败 / 未预期异常：保持 PROCESSING + 刷新时间戳，等定时补偿重试
                mediaFile.setAiStatus(AiStatus.PROCESSING.name());
                mediaFile.setAiProcessAt(LocalDateTime.now());
                mediaFileMapper.updateById(mediaFile);
            }
            if (e instanceof AiAnalysisException ae) {
                failedTaskService.record(mediaId, ae);
            }
            log.warn("AI 分析瞬时失败，保持 PROCESSING 等待补偿重试, mediaId={}, err={}", mediaId, e.getMessage());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
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
            // 内容级锁 + 归属复用：同一内容只转写一次；抢不到锁且无归属可复用则跳过
            String contentHash = mediaService.contentHash(mediaId);
            String text = transcribeWithReuse(mediaFile, contentHash, false);
            if (text == null) {
                // 没抢到内容级锁且无归属可复用：别人正在转写，跳过；结果最终落库，前端轮询可见
                log.info("同一内容已在转写中，跳过 mediaId={} contentHash={}", mediaId, contentHash);
                return;
            }
            // transcribeWithReuse 内部已落库 transcriptText / transcriptStatus 并登记归属
            evictCache(mediaFile);
            log.info("全文提取完成, mediaId={}", mediaId);

        } catch (Exception e) {
            log.error("全文提取失败, mediaId={}, err={}", mediaId, e.getMessage(), e);
            // 失败写状态字段 + 受控文案（不泄漏堆栈），不上抛（@Async 无消费层）
            mediaFile.setTranscriptStatus(AiStatus.FAILED.name());
            mediaFile.setTranscriptText("❌ 提取失败，请稍后重试");
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
     * @param wait        true=等待别人转写完成（分析链路，依赖转写结果）；false=不等待（独立转写链路）
     * @return 转写文本；null 表示未抢到锁且无归属可复用（别人正在转写）
     */
    private String transcribeWithReuse(MediaFile mediaFile, String contentHash, boolean wait) {
        RLock lock = redissonClient.getLock(AnalysisTaskKeys.contextLock(contentHash));
        boolean locked = false;
        try {
            locked = wait
                    ? lock.tryLock(CONTEXT_LOCK_WAIT_SECONDS, TimeUnit.SECONDS)
                    : lock.tryLock();
            // 无论是否抢到锁都先查归属：已有人完成则直接复用
            String reusable = resolveTranscript(mediaFile, contentHash);
            if (reusable != null) return reusable;
            if (!locked) return null; // 没抢到且没复用：别人在转写，跳过

            // 抢到锁且无归属：真正转写一次
            String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
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
            if (!isSuccessTranscript(owner)) {
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
        mediaFile.setAiSummary("❌ 分析失败，请稍后重试");
        // 若转写阶段尚未成功（即失败发生在 transcribe），同步置 FAILED，避免与 aiStatus 不一致
        if (!AiStatus.SUCCESS.name().equals(mediaFile.getTranscriptStatus())) {
            mediaFile.setTranscriptStatus(AiStatus.FAILED.name());
        }
        mediaFileMapper.updateById(mediaFile);
        evictCache(mediaFile);
        log.error("AI 分析失败, mediaId={}, err={}", mediaFile.getId(), e.getMessage(), e);
    }

    /**
     * 失效列表缓存，拼装 Key 规则与 MediaController.list 保持一致。
     */
    private void evictCache(MediaFile mediaFile) {
        String userIdStr = (mediaFile.getUserId() == null) ? "anon" : String.valueOf(mediaFile.getUserId());
        redisTemplate.delete("media:list:user:" + userIdStr);
    }
}
