package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("media_transcription")
public class MediaTranscription {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long mediaId;         // 关联 media_files.id
    private String status;        // NONE/PROCESSING/SUCCESS/FAILED
    private String transcriptText; // TEXT
    private LocalDateTime processAt;
    private Integer attempts;
    private Integer compensationAttempts;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
