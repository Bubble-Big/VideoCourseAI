package com.example.server.service;

import com.example.server.common.AiStatus;
import com.example.server.entity.MediaFile;
import com.example.server.exception.AiAnalysisException;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.strategy.AiAnalysisStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final MediaFileMapper mediaFileMapper;
    private final AiAnalysisStrategy aiAnalysisStrategy;
    // 【关键】必须注入 Redis 工具！
    private final StringRedisTemplate redisTemplate;

    public AiService(MediaFileMapper mediaFileMapper,
                     @Qualifier("defaultAiStrategy") AiAnalysisStrategy aiAnalysisStrategy,
                     StringRedisTemplate redisTemplate) {
        this.mediaFileMapper = mediaFileMapper;
        this.aiAnalysisStrategy = aiAnalysisStrategy;
        this.redisTemplate = redisTemplate;
    }

    /**
     * AI 分析：落库保证前端可见 + 异常上抛保证 MQ 可决策（两者解耦）。
     * <p>成功写 SUCCESS；失败先 {@link #markFailed} 落库，再把异常上抛给消费层决定重试或收敛。</p>
     */
    public void asyncAnalyze(Long mediaId) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            throw new AiAnalysisException("文件不存在: " + mediaId, false);
        }
        log.info("开始 AI 分析任务, mediaId={}", mediaId);

        // 进入处理态（不删缓存，避免中间态触发多余的 DB 查询）
        mediaFile.setAiStatus(AiStatus.PROCESSING.name());
        mediaFileMapper.updateById(mediaFile);

        try {
            // 1. 语音转文字（失败抛 AiAnalysisException，由 catch 统一落库 + 上抛）
            String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
            mediaFile.setTranscriptText(text);
            mediaFile.setTranscriptStatus(AiStatus.SUCCESS.name());

            // 2. 智能总结
            String summary = aiAnalysisStrategy.generateSummary(mediaFile.getFilePath());
            mediaFile.setAiSummary(summary);
            mediaFile.setAiStatus(AiStatus.SUCCESS.name());

            mediaFileMapper.updateById(mediaFile);
            evictCache(mediaFile);
            log.info("AI 分析完成, mediaId={}", mediaId);

        } catch (Exception e) {
            markFailed(mediaFile, e);
            // 上抛：保留 AiAnalysisException 的 retryable 标志；未预期异常按可重试包装
            if (e instanceof AiAnalysisException ae) {
                throw ae;
            }
            throw new AiAnalysisException("AI 分析失败", true, e);
        }
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
            // 只做语音转文字（失败抛 AiAnalysisException，由 catch 落库 FAILED）
            String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
            mediaFile.setTranscriptText(text);
            mediaFile.setTranscriptStatus(AiStatus.SUCCESS.name());

            mediaFileMapper.updateById(mediaFile);
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
