package com.example.server.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 媒体文件列表VO（不含TEXT字段，仅状态信息）
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

    // 关联状态（仅状态枚举，不含内容）
    private String aiStatus;
    private String transcriptStatus;
}
