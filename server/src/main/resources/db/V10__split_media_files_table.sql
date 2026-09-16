-- ============================================================
-- V10: media_files 表垂直拆分迁移
-- 执行前备份: mysqldump -u root -p media_db > backup_before_split.sql
-- ============================================================

USE media_db;

-- Step 1: 创建新表

-- 表1：media_ai_analysis（AI 分析表）
CREATE TABLE IF NOT EXISTS media_ai_analysis (
    id                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '分析记录ID',
    media_id                BIGINT       NOT NULL COMMENT '关联 media_files.id',
    status                  VARCHAR(32)  DEFAULT 'NONE' COMMENT 'AI分析状态: NONE/PENDING/PROCESSING/SUCCESS/FAILED',
    summary                 TEXT         DEFAULT NULL COMMENT 'AI 智能总结(Markdown)',
    process_at              DATETIME     DEFAULT NULL COMMENT '最近一次处理尝试时间',
    attempts                INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数（用户手动重试 + MQ 重投）',
    compensation_attempts   INT          NOT NULL DEFAULT 0 COMMENT '补偿调度器专属重试计数',
    retry_count             INT          NOT NULL DEFAULT 0 COMMENT '用户手动重试计数器',
    created_at              DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    updated_at              DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_id (media_id),
    KEY idx_status_process (status, process_at) COMMENT '补偿调度器查询索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 分析状态与结果表';

-- 表2：media_transcription（转写表）
CREATE TABLE IF NOT EXISTS media_transcription (
    id                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '转写记录ID',
    media_id                BIGINT       NOT NULL COMMENT '关联 media_files.id',
    status                  VARCHAR(32)  DEFAULT 'NONE' COMMENT '转写状态: NONE/PROCESSING/SUCCESS/FAILED',
    transcript_text         TEXT         DEFAULT NULL COMMENT '语音转写全文',
    process_at              DATETIME     DEFAULT NULL COMMENT '最近一次处理尝试时间',
    attempts                INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    compensation_attempts   INT          NOT NULL DEFAULT 0 COMMENT '补偿调度器专属重试计数',
    retry_count             INT          NOT NULL DEFAULT 0 COMMENT '用户手动重试计数器',
    created_at              DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    updated_at              DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_id (media_id),
    KEY idx_status_process (status, process_at) COMMENT '补偿调度器查询索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='语音转写状态与结果表';

-- Step 2: 数据迁移 —— 保留原表字段，拆分数据到新表
START TRANSACTION;

-- 2.1 迁移 AI 分析数据
INSERT INTO media_ai_analysis (
    media_id, status, summary, process_at, attempts,
    compensation_attempts, retry_count, created_at, updated_at
)
SELECT
    id AS media_id,
    COALESCE(ai_status, 'NONE') AS status,
    ai_summary AS summary,
    ai_process_at AS process_at,
    COALESCE(ai_attempts, 0) AS attempts,
    COALESCE(compensation_attempts, 0) AS compensation_attempts,
    COALESCE(analysis_retry_count, 0) AS retry_count,
    upload_time AS created_at,
    NOW() AS updated_at
FROM media_files;

-- 2.2 迁移转写数据
INSERT INTO media_transcription (
    media_id, status, transcript_text, process_at, attempts,
    compensation_attempts, retry_count, created_at, updated_at
)
SELECT
    id AS media_id,
    COALESCE(transcript_status, 'NONE') AS status,
    transcript_text AS transcript_text,
    transcript_process_at AS process_at,
    0 AS attempts,
    COALESCE(transcript_compensation_attempts, 0) AS compensation_attempts,
    COALESCE(transcript_retry_count, 0) AS retry_count,
    upload_time AS created_at,
    NOW() AS updated_at
FROM media_files;

-- 2.3 保留原表核心字段（DROP COLUMN 方式）
ALTER TABLE media_files
    DROP COLUMN ai_status,
    DROP COLUMN ai_summary,
    DROP COLUMN ai_process_at,
    DROP COLUMN ai_attempts,
    DROP COLUMN compensation_attempts,
    DROP COLUMN analysis_retry_count,
    DROP COLUMN transcript_status,
    DROP COLUMN transcript_text,
    DROP COLUMN transcript_process_at,
    DROP COLUMN transcript_compensation_attempts,
    DROP COLUMN transcript_retry_count;

COMMIT;

-- Step 3: 验证数据一致性
SELECT
    (SELECT COUNT(*) FROM media_files) AS files_count,
    (SELECT COUNT(*) FROM media_ai_analysis) AS analysis_count,
    (SELECT COUNT(*) FROM media_transcription) AS transcription_count;
