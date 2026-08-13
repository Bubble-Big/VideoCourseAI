package com.example.server.exception;

/**
 * AI 分析链路专用异常，携带「是否可重试」语义。
 * <p>区别于 {@link BusinessException}（携带 ErrorCode、面向同步接口的全局异常处理），
 * 本异常在异步消费链路（VideoAnalysisConsumer）做最终决策：
 * retryable=false 判为永久失败写台账；retryable=true 上抛交给 RocketMQ 重投。</p>
 */
public class AiAnalysisException extends RuntimeException {

    /** 是否可重试：true=瞬时失败可重投，false=确定性错误收敛 */
    private final boolean retryable;

    public AiAnalysisException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public AiAnalysisException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
