package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("media_ai_analysis")
public class MediaAiAnalysis {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long mediaId;         // 关联 media_files.id
    private String status;        // NONE/PENDING/PROCESSING/SUCCESS/FAILED
    private String summary;       // TEXT
    private LocalDateTime processAt;
    private Integer attempts;
    private Integer compensationAttempts;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
