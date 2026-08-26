package com.example.server.service;

import com.example.server.common.AiStatus;
import com.example.server.entity.MediaFile;
import com.example.server.exception.AiAnalysisException;
import com.example.server.mapper.MediaFileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 分析补偿调度器：定时扫「卡死」记录（PENDING/PROCESSING 且超过阈值未更新），
 * 重新触发或落失败，取代 RocketMQ 的 reconsumeTimes 重投。
 * <p>重试决策的核心：ai_attempts 由本触发侧统一 +1；触发前先刷新 ai_process_at，
 * 把同一 contentHash 的触发频率锁死为「每阈值间隔一次」，避免队列堆积。</p>
 */
@Component
public class AnalysisCompensationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisCompensationScheduler.class);

    /** 卡死阈值（分钟）：必须大于最长正常执行时间（15min），避免误杀仍在正常跑的任务。 */
    @Value("${ai.compensation.threshold-minutes:20}")
    private long thresholdMinutes = 20;

    /** 最大尝试次数：达到后不再重试，直接落 FAILED。 */
    @Value("${ai.compensation.max-attempts:3}")
    private int maxAttempts = 3;

    /** 单轮扫描上限，防止一次扫出过多记录。 */
    private static final int SCAN_LIMIT = 100;

    private final MediaFileMapper mediaFileMapper;
    private final AiService aiService;
    private final FailedAnalysisTaskService failedTaskService;

    public AnalysisCompensationScheduler(MediaFileMapper mediaFileMapper,
                                         AiService aiService,
                                         FailedAnalysisTaskService failedTaskService) {
        this.mediaFileMapper = mediaFileMapper;
        this.aiService = aiService;
        this.failedTaskService = failedTaskService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void compensate() {
        LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofMinutes(thresholdMinutes));
        List<MediaFile> stalled = mediaFileMapper.selectStalledAnalysis(threshold, SCAN_LIMIT);
        if (stalled.isEmpty()) {
            return;
        }
        log.info("补偿调度器扫到 {} 条卡死记录", stalled.size());
        for (MediaFile f : stalled) {
            compensateOne(f);
        }
    }

    private void compensateOne(MediaFile f) {
        // 触发侧：先 +1 计数并刷新时间戳，再决定「触发」还是「落 FAILED」。
        // 刷新 ai_process_at 是关键——切断「排队不执行 → 补偿每分钟重复触发」的正反馈。
        int attempts = (f.getAiAttempts() == null ? 0 : f.getAiAttempts()) + 1;
        f.setAiAttempts(attempts);
        f.setAiProcessAt(LocalDateTime.now());
        if (attempts >= maxAttempts) {
            // 重试耗尽 → 落 FAILED + 台账
            f.setAiStatus(AiStatus.FAILED.name());
            f.setAiSummary("❌ 分析失败，请稍后重试");
            mediaFileMapper.updateById(f);
            failedTaskService.record(f.getId(), new AiAnalysisException("重试耗尽，判定失败", false));
            log.warn("分析任务重试耗尽，落失败, mediaId={}", f.getId());
            return;
        }
        mediaFileMapper.updateById(f);       // 先落 attempts + ai_process_at
        aiService.asyncAnalyze(f.getId());  // 再重新触发（@Async 异步执行）
    }
}
