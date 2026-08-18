package com.example.server.service;

import java.time.Duration;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 令牌桶限流统一入口：用户级 + 全局级双层准入控制。
 * <p>限流是「用户级 + 全局级」准入控制，与「内容」无关，key 固定为用户维度 / 全局维度，不涉及 contentHash。</p>
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    // AI 分析配额（transcribe + summary 一次完整分析）
    private static final int AI_USER_PER_MINUTE = 5;
    private static final int AI_GLOBAL_PER_MINUTE = 30;
    // 纯文字提取配额（仅 ASR，成本低于完整分析）
    private static final int TRANSCRIBE_USER_PER_MINUTE = 10;
    private static final int TRANSCRIBE_GLOBAL_PER_MINUTE = 60;

    private final RedissonClient redissonClient;

    public RateLimitService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** AI 分析配额：用户级 + 全局级双层。超限抛 RATE_LIMITED，Redis 异常抛 SERVICE_UNAVAILABLE。 */
    public void requireAiQuota(Long userId) {
        tryAcquire("limit:ai:user:", "limit:ai:global",
                AI_USER_PER_MINUTE, AI_GLOBAL_PER_MINUTE, userId, "AI 分析");
    }

    /** 文字提取配额：用户级 + 全局级双层。 */
    public void requireTranscribeQuota(Long userId) {
        tryAcquire("limit:transcribe:user:", "limit:transcribe:global",
                TRANSCRIBE_USER_PER_MINUTE, TRANSCRIBE_GLOBAL_PER_MINUTE, userId, "文字提取");
    }

    private void tryAcquire(String userKeyPrefix, String globalKey,
                            int userRate, int globalRate, Long userId, String label) {
        try {
            RRateLimiter userLimiter = redissonClient.getRateLimiter(userKeyPrefix + uid(userId));
            userLimiter.trySetRate(RateType.OVERALL, userRate, Duration.ofMinutes(1));
            if (!userLimiter.tryAcquire()) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, label + "请求过于频繁，请稍后再试");
            }
            RRateLimiter globalLimiter = redissonClient.getRateLimiter(globalKey);
            globalLimiter.trySetRate(RateType.OVERALL, globalRate, Duration.ofMinutes(1));
            if (!globalLimiter.tryAcquire()) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, "系统繁忙，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e; // 真超限，原样抛出
        } catch (RuntimeException e) {
            log.warn("rate_limiter_unavailable label={} userId={}", label, userId, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, label + "限流器暂不可用，请稍后再试");
        }
    }

    private String uid(Long userId) {
        return userId == null ? "anon" : String.valueOf(userId);
    }
}
