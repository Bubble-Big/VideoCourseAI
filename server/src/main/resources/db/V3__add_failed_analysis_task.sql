-- ============================================================
-- AI 分析失败台账迁移脚本
-- 新增失败记录表，作为消费层永久失败的落点（对标 DOVideo-AI 的 FailedAnalysisTask）
-- ============================================================

CREATE TABLE IF NOT EXISTS failed_analysis_task (
    id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '失败记录ID',
    media_id   BIGINT        NOT NULL COMMENT '关联 media_files.id',
    error_type VARCHAR(64)   DEFAULT NULL COMMENT '异常类型（AiAnalysisException/Exception 等）',
    error_msg  VARCHAR(2000) DEFAULT NULL COMMENT '错误摘要（受控，不含堆栈）',
    attempts   INT           DEFAULT 1 COMMENT '累计投递次数',
    created_at DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '首次失败时间',
    PRIMARY KEY (id),
    KEY idx_media_id (media_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 分析失败台账';
