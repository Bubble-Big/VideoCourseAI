package com.example.server.common;

/**
 * AI 分析失败阶段/来源分类。
 * <p>用于失败台账 {@code errorType} 区分失败源，替代原来恒为「AiAnalysisException」的类名，
 * 让排查能一眼定位失败发生在哪个环节。</p>
 */
public enum AiFailStage {

    /** 文件不存在 / 路径为空 */
    FILE,

    /** 转写锁等待超时 */
    LOCK,

    /** FFmpeg 提取音频失败 */
    FFMPEG,

    /** 语音转文字（ASR）失败 */
    ASR,

    /** 大模型总结（DeepSeek）失败 */
    LLM,

    /** 未分类兜底 */
    UNKNOWN
}
