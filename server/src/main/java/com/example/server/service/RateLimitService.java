package com.example.server.service;

import java.time.Duration;

import com.example.server.common.ErrorCode;
import com.example.server.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 令牌桶限流统一入口：用户级 + 全局级双层准入控制。
 * <p>限流是「用户级 + 全局级」准入控制，与「内容」无关，key 固定为用户维度 / 全局维度，不涉及 contentHash。</p>
 * <p>阈值经构造器从配置文件注入（{@code ai.rate-limit.*}），改配置即可调整，无需改代码。</p>
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    /** 全局级限流器 key（固定，启动时预置 rate）。 */
    private static final String AI_GLOBAL_KEY = "limit:ai:global";
    private static final String TRANSCRIBE_GLOBAL_KEY = "limit:transcribe:global";

    private final RedissonClient redissonClient;
    // AI 分析配额（transcribe + summary 一次完整分析）
    private final int aiUserPerMinute;
    private final int aiGlobalPerMinute;
    // 纯文字提取配额（仅 ASR，成本低于完整分析）
    private final int transcribeUserPerMinute;
    private final int transcribeGlobalPerMinute;

    public RateLimitService(RedissonClient redissonClient,
                            @Value("${ai.rate-limit.ai-user-per-minute:5}") int aiUserPerMinute,
                            @Value("${ai.rate-limit.ai-global-per-minute:30}") int aiGlobalPerMinute,
                            @Value("${ai.rate-limit.transcribe-user-per-minute:10}") int transcribeUserPerMinute,
                            @Value("${ai.rate-limit.transcribe-global-per-minute:60}") int transcribeGlobalPerMinute) {
        this.redissonClient = redissonClient;
        this.aiUserPerMinute = aiUserPerMinute;
        this.aiGlobalPerMinute = aiGlobalPerMinute;
        this.transcribeUserPerMinute = transcribeUserPerMinute;
        this.transcribeGlobalPerMinute = transcribeGlobalPerMinute;
    }

    /**
     * 启动时预置全局级限流器 rate（key 固定）。
     * <p>用 {@code setRate} 强制覆盖：热路径不再重复初始化，改配置后重启即生效。</p>
     */
    @PostConstruct
    public void initGlobalLimiters() {
        redissonClient.getRateLimiter(AI_GLOBAL_KEY).setRate(RateType.OVERALL, aiGlobalPerMinute, Duration.ofMinutes(1));
        redissonClient.getRateLimiter(TRANSCRIBE_GLOBAL_KEY).setRate(RateType.OVERALL, transcribeGlobalPerMinute, Duration.ofMinutes(1));
    }

    /** AI 分析配额：用户级 + 全局级双层。超限抛 RATE_LIMITED，Redis 异常抛 SERVICE_UNAVAILABLE。 */
    public void requireAiQuota(Long userId) {
        tryAcquire("limit:ai:user:", AI_GLOBAL_KEY, aiUserPerMinute, userId, "AI 分析");
    }

    /** 文字提取配额：用户级 + 全局级双层。 */
    public void requireTranscribeQuota(Long userId) {
        tryAcquire("limit:transcribe:user:", TRANSCRIBE_GLOBAL_KEY, transcribeUserPerMinute, userId, "文字提取");
    }

    private void tryAcquire(String userKeyPrefix, String globalKey, int userRate, Long userId, String label) {
        try {
            // 用户级：key 动态，懒初始化；setRate 强制覆盖保证改配置立即生效
            RRateLimiter userLimiter = redissonClient.getRateLimiter(userKeyPrefix + uid(userId));
            userLimiter.setRate(RateType.OVERALL, userRate, Duration.ofMinutes(1));
            if (!userLimiter.tryAcquire()) {
                throw new BusinessException(ErrorCode.RATE_LIMITED, label + "请求过于频繁，请稍后再试");
            }
            // 全局级：rate 已在 @PostConstruct 预置，这里只消费令牌
            RRateLimiter globalLimiter = redissonClient.getRateLimiter(globalKey);
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
