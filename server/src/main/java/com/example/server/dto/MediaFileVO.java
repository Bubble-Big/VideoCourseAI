package com.example.server.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 媒体文件列表VO（包含文本内容）
 */
@Data
public class MediaFileVO {
    private Long id;
    private Long userId;
    private String filename;
    private String status;
    private String filePath;
    private Long fileSize;
    private String fileMd5;
    private String coverUrl;
    private LocalDateTime uploadTime;

    // AI 分析
    private String aiStatus;
    private String aiSummary;

    // 文字转写
    private String transcriptStatus;
    private String transcriptText;
}
