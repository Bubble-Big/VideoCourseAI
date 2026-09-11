package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.common.AiStatus;
import com.example.server.common.GateOutcome;
import com.example.server.entity.MediaFile;
import com.example.server.exception.AiAnalysisException;
import com.example.server.mapper.MediaFileMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * AI 分析补偿调度器：定时扫「卡死」记录（PENDING/PROCESSING 且超过阈值未更新），
 * 重新触发或落失败，取代 RocketMQ 的 reconsumeTimes 重投。
 * <p>加固版修复清单：</p>
 * <ul>
 *   <li>问题 1：乐观锁版本号，防丢失更新（原任务刚写入的成功结果被补偿调度器旧快照覆盖）</li>
 *   <li>问题 2：重试计数绑定执行结果（只有真正执行且失败才计数，DEFER/REUSE 不消耗）</li>
 *   <li>问题 4：分布式锁包裹整轮扫描（多实例部署下互斥）</li>
 *   <li>问题 5：compensation_attempts 专属计数，与用户手动重试 ai_attempts 分离</li>
 *   <li>问题 6：单条记录异常隔离，不中断整轮循环</li>
 *   <li>问题 7：线程池拒绝异常不消耗计数（按结果计数自然覆盖）</li>
 * </ul>
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
    private final TaskEventService taskEventService;
    private final RedissonClient redissonClient;

    public AnalysisCompensationScheduler(MediaFileMapper mediaFileMapper,
                                         AiService aiService,
                                         FailedAnalysisTaskService failedTaskService,
                                         TaskEventService taskEventService,
                                         RedissonClient redissonClient) {
        this.mediaFileMapper = mediaFileMapper;
        this.aiService = aiService;
        this.failedTaskService = failedTaskService;
        this.taskEventService = taskEventService;
        this.redissonClient = redissonClient;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void compensate() {
        // 问题 4：分布式锁包裹整轮扫描，多实例部署下互斥
        RLock schedulerLock = redissonClient.getLock("lock:scheduler:analysis-compensation");
        boolean locked;
        try {
            locked = schedulerLock.tryLock(0, 50, TimeUnit.SECONDS);   // 抢不到立即放弃，锁 50s 自动释放兜底进程崩溃
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!locked) {
            return;   // 其他实例正在跑本轮扫描，本实例跳过
        }
        try {
            LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofMinutes(thresholdMinutes));
            List<MediaFile> stalled = mediaFileMapper.selectStalledAnalysis(threshold, SCAN_LIMIT);
            if (stalled.isEmpty()) {
                return;
            }
            log.info("补偿调度器扫到 {} 条卡死记录", stalled.size());
            for (MediaFile f : stalled) {
                try {
                    compensateOne(f);
                } catch (Exception e) {
                    // 问题 6：单条记录异常隔离，不中断整轮循环
                    log.warn("单条补偿处理异常，mediaId={}, err={}", f.getId(), e.getMessage(), e);
                }
            }
        } finally {
            if (schedulerLock.isHeldByCurrentThread()) {
                schedulerLock.unlock();
            }
        }
    }

    private void compensateOne(MediaFile f) {
        Long mediaId = f.getId();
        // 问题 2：先刷新 ai_process_at 阻断「排队不执行→每分钟被重复扫到」的正反馈，但不在此处递增 attempts
        f.setAiProcessAt(LocalDateTime.now());
        int updated = mediaFileMapper.update(null, new LambdaUpdateWrapper<MediaFile>()
            .eq(MediaFile::getId, f.getId())
            .eq(MediaFile::getVersion, f.getVersion())
            .set(MediaFile::getAiProcessAt, LocalDateTime.now()));
        if (updated == 0) {
            // 问题 1：版本冲突，说明原任务在查询快照之后已经写入了新结果，补偿调度器的这次判断已经过期
            log.info("补偿刷新时间戳被跳过（版本冲突），mediaId={}", mediaId);
            return;
        }

        CompletableFuture<GateOutcome> future = aiService.asyncAnalyze(mediaId);
        future.whenComplete((outcome, ex) -> {
            if (ex != null || outcome == GateOutcome.DEFER) {
                // 异常或让位：这次触发没有产生任何真实进展，不消耗 attempts，交下一轮重新判断
                log.warn("补偿触发未产生进展（DEFER/异常），mediaId={}, outcome={}", mediaId, outcome, ex);
                return;
            }
            if (outcome == GateOutcome.REUSE) {
                return;   // 复用他人结果，本来就不算失败重试，无需计数
            }
            // outcome == PROCEED：说明真正跑完了一次流程。
            // 若这次执行内部已经把状态写成 SUCCESS，此处的 attempts 增量不影响终态；
            // 若写成了 PROCESSING（asyncAnalyze 内部瞬时失败分支），说明这确实是一次有效的失败重试，需要计数。
            incrementAttemptsIfStillPending(mediaId);
        });
    }

    private void incrementAttemptsIfStillPending(Long mediaId) {
        MediaFile latest = mediaFileMapper.selectById(mediaId);
        if (latest == null || !AiStatus.PROCESSING.name().equals(latest.getAiStatus())) {
            return;   // 已经是 SUCCESS/FAILED 等终态，不需要补偿再计数
        }
        // 问题 5：使用 compensationAttempts 专属计数，不再混用 aiAttempts
        int attempts = (latest.getCompensationAttempts() == null ? 0 : latest.getCompensationAttempts()) + 1;
        latest.setCompensationAttempts(attempts);
        LambdaUpdateWrapper<MediaFile> wrapper = new LambdaUpdateWrapper<MediaFile>()
            .eq(MediaFile::getId, latest.getId())
            .eq(MediaFile::getVersion, latest.getVersion())
            .set(MediaFile::getCompensationAttempts, attempts);
        if (attempts >= maxAttempts) {
            latest.setAiStatus(AiStatus.FAILED.name());
            latest.setAiSummary(null);
            wrapper.set(MediaFile::getAiStatus, AiStatus.FAILED.name())
                   .set(MediaFile::getAiSummary, null);
        }
        int updated = mediaFileMapper.update(null, wrapper);
        if (updated == 0) {
            return;   // 版本冲突：这段时间内状态又被改写，放弃本次计数
        }
        if (attempts >= maxAttempts) {
            failedTaskService.record(mediaId, new AiAnalysisException("重试耗尽，判定失败", false), attempts);

            // SSE 推送：补偿重试耗尽 FAILED
            taskEventService.publishAnalysis(mediaId, AiStatus.FAILED.name(), null, "重试耗尽，判定失败");
        }
    }
}
