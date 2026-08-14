package com.example.server.strategy;

/**
 * AI 分析策略接口
 * 修改：参数改为 String 类型，兼容本地路径 (D:/...) 和网络 URL (http://...)
 */
public interface AiAnalysisStrategy {

    /**
     * 将视频文件转换为文字
     * @param videoPath 视频路径或URL
     */
    String transcribe(String videoPath);

    /**
     * 对视频内容进行智能总结
     * @param videoPath 视频路径或URL
     */
    String generateSummary(String videoPath);

    /**
     * 基于已转写的文本进行智能总结（复用 transcribe 结果，避免重复提取音频 + ASR）。
     * @param text 已转写的文本
     */
    String generateSummaryFromText(String text);
}