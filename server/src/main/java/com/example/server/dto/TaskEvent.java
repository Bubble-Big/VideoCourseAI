package com.example.server.dto;

/**
 * SSE 任务事件数据传输对象
 *
 * @param mediaId 媒体文件 ID（前端需要识别是哪个文件的事件）
 * @param state 任务状态：NONE / PENDING / PROCESSING / SUCCESS / FAILED
 * @param transcriptText 文字提取结果（仅 transcription 类型有值）
 * @param aiSummary AI 分析结果（仅 analysis 类型有值）
 * @param error 错误信息（state=FAILED 时携带）
 * @param timestamp 事件时间戳（毫秒）
 */
public record TaskEvent(
    Long mediaId,
    String state,
    String transcriptText,
    String aiSummary,
    String error,
    Long timestamp
) {
    /**
     * 创建 AI 分析事件
     */
    public static TaskEvent analysis(Long mediaId, String state, String aiSummary, String error) {
        return new TaskEvent(mediaId, state, null, aiSummary, error, System.currentTimeMillis());
    }

    /**
     * 创建文字提取事件
     */
    public static TaskEvent transcription(Long mediaId, String state, String transcriptText, String error) {
        return new TaskEvent(mediaId, state, transcriptText, null, error, System.currentTimeMillis());
    }

    /**
     * 判断是否为终态（SUCCESS/FAILED 后自动关闭 SSE 连接）
     */
    public boolean isTerminal() {
        return "SUCCESS".equals(state) || "FAILED".equals(state);
    }
}
