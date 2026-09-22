package com.example.server.common;

/**
 * AI 分析 / 文字提取的通用状态枚举。
 * <p>用于替代原先「状态混在 aiSummary / transcriptText 文案里」的做法，
 * 让状态成为独立字段，前端按状态判断而非字符串匹配。</p>
 *
 * <p>AI 分析流转：NONE → PENDING(投递 MQ) → PROCESSING(消费者接单) → SUCCESS / FAILED</p>
 * <p>文字提取流转：NONE → PROCESSING(提交线程池) → SUCCESS / FAILED（无 PENDING 阶段）</p>
 */
public enum AiStatus {
    /** 未分析 / 未提取 */
    NONE,
    /** 已投递 MQ，排队中（仅 AI 分析使用） */
    PENDING,
    /** 正在处理中 */
    PROCESSING,
    /** 处理完成 */
    SUCCESS,
    /** 处理失败 */
    FAILED
}
