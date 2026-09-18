-- ============================================================
-- V11: 子表乐观锁字段补充
-- 修复补偿调度器并发冲突问题
-- ============================================================

USE media_db;

-- 为 AI 分析表添加 version 字段
ALTER TABLE media_ai_analysis
ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';

-- 为转写表添加 version 字段
ALTER TABLE media_transcription
ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';

-- 验证字段添加成功
SELECT
    'media_ai_analysis' AS table_name,
    COUNT(*) AS row_count,
    MAX(version) AS max_version
FROM media_ai_analysis
UNION ALL
SELECT
    'media_transcription',
    COUNT(*),
    MAX(version)
FROM media_transcription;
