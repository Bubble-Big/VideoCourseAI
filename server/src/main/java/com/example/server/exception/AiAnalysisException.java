package com.example.server.exception;

import com.example.server.common.AiFailStage;

/**
 * AI 分析链路专用异常，携带「是否可重试」+「失败阶段」语义。
 * <p>区别于 {@link BusinessException}（携带 ErrorCode、面向同步接口的全局异常处理），
 * 本异常在异步消费链路（VideoAnalysisConsumer）做最终决策：
 * retryable=false 判为永久失败写台账；retryable=true 上抛交给 RocketMQ 重投。</p>
 */
public class AiAnalysisException extends RuntimeException {

    /** 是否可重试：true=瞬时失败可重投，false=确定性错误收敛 */
    private final boolean retryable;

    /** 失败阶段/来源：ASR/LLM/FFMPEG/FILE/LOCK/UNKNOWN，落台账区分失败源 */
    private final AiFailStage stage;

    public AiAnalysisException(String message, boolean retryable) {
        this(message, retryable, AiFailStage.UNKNOWN);
    }

    public AiAnalysisException(String message, boolean retryable, AiFailStage stage) {
        super(message);
        this.retryable = retryable;
        this.stage = stage;
    }

    public AiAnalysisException(String message, boolean retryable, Throwable cause) {
        this(message, retryable, AiFailStage.UNKNOWN, cause);
    }

    public AiAnalysisException(String message, boolean retryable, AiFailStage stage, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.stage = stage;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public AiFailStage getStage() {
        return stage;
    }
}
