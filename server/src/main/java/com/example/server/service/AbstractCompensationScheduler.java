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
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 补偿调度器抽象基类：封装通用逻辑（分布式锁、扫描循环、乐观锁、retryCount 冲突检测）。
 * 子类通过实现抽象方法定制化查询条件、触发重试、字段访问等。
 */
public abstract class AbstractCompensationScheduler {

    protected static final Logger log = LoggerFactory.getLogger(AbstractCompensationScheduler.class);
    protected static final int SCAN_LIMIT = 100;

    protected final MediaFileMapper mediaFileMapper;
    protected final RedissonClient redissonClient;
    protected final TaskEventService taskEventService;

    public AbstractCompensationScheduler(MediaFileMapper mediaFileMapper,
                                        RedissonClient redissonClient,
                                        TaskEventService taskEventService) {
        this.mediaFileMapper = mediaFileMapper;
        this.redissonClient = redissonClient;
        this.taskEventService = taskEventService;
    }

    // ========== 子类配置 ==========

    /** 分布式锁键名 */
    protected abstract String getLockKey();

    /** 卡死阈值（分钟） */
    protected abstract long getThresholdMinutes();

    /** 最大重试次数 */
    protected abstract int getMaxAttempts();

    /** 调度器名称（用于日志） */
    protected abstract String getSchedulerName();

    // ========== 子类查询方法 ==========

    /** 扫描卡死任务 */
    protected abstract List<MediaFile> scanStalledTasks(LocalDateTime threshold, int limit);

    /** 触发重试（返回 CompletableFuture 用于异步回调） */
    protected abstract CompletableFuture<?> triggerRetry(Long mediaId);

    // ========== 子类字段访问器 ==========

    protected abstract String getStatus(MediaFile file);
    protected abstract Integer getCompensationAttempts(MediaFile file);
    protected abstract Integer getRetryCount(MediaFile file);
    protected abstract LocalDateTime getProcessAt(MediaFile file);

    // ========== 子类字段设置器 ==========

    protected abstract void setStatus(LambdaUpdateWrapper<MediaFile> wrapper, String status);
    protected abstract void setCompensationAttempts(LambdaUpdateWrapper<MediaFile> wrapper, int attempts);
    protected abstract void setProcessAt(LambdaUpdateWrapper<MediaFile> wrapper, LocalDateTime time);

    /**
     * 刷新时间戳字段：AI 分析用 ai_process_at，文字提取用 transcript_process_at
     */
    protected abstract void refreshProcessAtField(LambdaUpdateWrapper<MediaFile> wrapper, LocalDateTime time);

    // ========== 子类失败处理 ==========

    protected abstract void recordFailure(Long mediaId, Exception ex, int attempts);
    protected abstract void publishFailure(Long mediaId, String errorMsg);

    // ========== 通用逻辑（所有补偿调度器共享）==========

    /**
     * 补偿调度主流程：分布式锁 + 扫描 + 逐个补偿
     */
    public void compensate() {
        RLock lock = redissonClient.getLock(getLockKey());
        boolean locked;
        try {
            locked = lock.tryLock(0, 50, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!locked) {
            return;
        }

        try {
            LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofMinutes(getThresholdMinutes()));
            List<MediaFile> stalled = scanStalledTasks(threshold, SCAN_LIMIT);
            if (stalled.isEmpty()) {
                return;
            }
            log.info("{}扫到 {} 条卡死记录", getSchedulerName(), stalled.size());
            for (MediaFile f : stalled) {
                try {
                    compensateOne(f);
                } catch (Exception e) {
                    log.warn("单条补偿处理异常, mediaId={}, err={}", f.getId(), e.getMessage(), e);
                }
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 单条记录补偿：刷新时间戳 + 触发重试 + 异步回调计数
     */
    private void compensateOne(MediaFile f) {
        Long mediaId = f.getId();
        Integer snapshotRetryCount = getRetryCount(f);

        // 刷新时间戳（乐观锁）
        LambdaUpdateWrapper<MediaFile> wrapper = new LambdaUpdateWrapper<MediaFile>()
            .eq(MediaFile::getId, f.getId())
            .eq(MediaFile::getVersion, f.getVersion());
        refreshProcessAtField(wrapper, LocalDateTime.now());

        int updated = mediaFileMapper.update(null, wrapper);

        if (updated == 0) {
            log.info("{}刷新时间戳被跳过（版本冲突）, mediaId={}", getSchedulerName(), mediaId);
            return;
        }

        // 触发重试
        CompletableFuture<?> future = triggerRetry(mediaId);
        future.whenComplete((outcome, ex) -> {
            if (shouldSkipIncrement(outcome, ex)) {
                log.warn("补偿触发未产生进展（DEFER/异常/REUSE），mediaId={}", mediaId);
                return;
            }
            incrementAttemptsIfStillPending(mediaId, snapshotRetryCount);
        });
    }

    /**
     * 判断是否跳过计数递增（DEFER/REUSE/异常）
     */
    private boolean shouldSkipIncrement(Object outcome, Throwable ex) {
        if (ex != null) return true;
        if (outcome instanceof GateOutcome) {
            GateOutcome gate = (GateOutcome) outcome;
            return gate == GateOutcome.DEFER || gate == GateOutcome.REUSE;
        }
        return false;
    }

    /**
     * 递增重试计数（retryCount 冲突检测 + 乐观锁 + 达到上限标记 FAILED）
     */
    private void incrementAttemptsIfStillPending(Long mediaId, Integer snapshotRetryCount) {
        MediaFile latest = mediaFileMapper.selectById(mediaId);
        if (latest == null || !AiStatus.PROCESSING.name().equals(getStatus(latest))) {
            return;
        }

        // P1 修复：retryCount 冲突检测
        Integer currentRetryCount = getRetryCount(latest);
        Integer originalRetryCount = (snapshotRetryCount == null ? 0 : snapshotRetryCount);
        currentRetryCount = (currentRetryCount == null ? 0 : currentRetryCount);

        if (!currentRetryCount.equals(originalRetryCount)) {
            log.info("检测到 retryCount 变化（{}→{}），用户已手动重试，跳过补偿计数 mediaId={}",
                originalRetryCount, currentRetryCount, mediaId);
            return;
        }

        // 递增计数
        int attempts = (getCompensationAttempts(latest) == null ? 0 : getCompensationAttempts(latest)) + 1;
        LambdaUpdateWrapper<MediaFile> wrapper = new LambdaUpdateWrapper<MediaFile>()
            .eq(MediaFile::getId, latest.getId())
            .eq(MediaFile::getVersion, latest.getVersion());

        setCompensationAttempts(wrapper, attempts);

        if (attempts >= getMaxAttempts()) {
            setStatus(wrapper, AiStatus.FAILED.name());
        }

        int updated = mediaFileMapper.update(null, wrapper);
        if (updated == 0) {
            return;
        }

        if (attempts >= getMaxAttempts()) {
            recordFailure(mediaId, new AiAnalysisException("重试耗尽，判定失败", false), attempts);
            publishFailure(mediaId, "重试耗尽，判定失败");
        }
    }
}
