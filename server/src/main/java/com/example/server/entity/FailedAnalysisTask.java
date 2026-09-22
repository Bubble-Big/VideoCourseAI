package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 分析失败台账实体。
 * <p>作为消费层永久失败的落点，用于失败任务排查与人工重放。</p>
 */
@Data
@TableName("failed_analysis_task")
public class FailedAnalysisTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long mediaId;          // 关联 media_files.id
    private String errorType;      // 失败阶段（AiFailStage：ASR/LLM/FFMPEG/FILE/LOCK/UNKNOWN）
    private String errorMsg;       // 错误摘要（受控，不含堆栈）
    private Integer attempts;      // 补偿重试次数（0=首次失败未重试，N=补偿重试 N 次）
    private LocalDateTime createdAt; // 首次失败时间（数据库自动填充）
}
