-- ⚠️ 紧急回滚方案（需提前备份）
USE media_db;

START TRANSACTION;

-- 1. 恢复被删除的列
ALTER TABLE media_files
    ADD COLUMN ai_status VARCHAR(32) DEFAULT 'NONE' COMMENT 'AI分析状态',
    ADD COLUMN ai_summary TEXT DEFAULT NULL COMMENT 'AI总结',
    ADD COLUMN ai_process_at DATETIME DEFAULT NULL COMMENT 'AI处理时间',
    ADD COLUMN ai_attempts INT DEFAULT 0 COMMENT 'AI尝试次数',
    ADD COLUMN compensation_attempts INT DEFAULT 0 COMMENT '补偿重试次数',
    ADD COLUMN analysis_retry_count INT DEFAULT 0 COMMENT '用户重试次数',
    ADD COLUMN transcript_status VARCHAR(32) DEFAULT 'NONE' COMMENT '转写状态',
    ADD COLUMN transcript_text TEXT DEFAULT NULL COMMENT '转写文本',
    ADD COLUMN transcript_process_at DATETIME DEFAULT NULL COMMENT '转写处理时间',
    ADD COLUMN transcript_compensation_attempts INT DEFAULT 0 COMMENT '转写补偿重试',
    ADD COLUMN transcript_retry_count INT DEFAULT 0 COMMENT '转写用户重试';

-- 2. 回填数据
UPDATE media_files mf
LEFT JOIN media_ai_analysis ma ON mf.id = ma.media_id
LEFT JOIN media_transcription mt ON mf.id = mt.media_id
SET
    mf.ai_status = ma.status,
    mf.ai_summary = ma.summary,
    mf.ai_process_at = ma.process_at,
    mf.ai_attempts = ma.attempts,
    mf.compensation_attempts = ma.compensation_attempts,
    mf.analysis_retry_count = ma.retry_count,
    mf.transcript_status = mt.status,
    mf.transcript_text = mt.transcript_text,
    mf.transcript_process_at = mt.process_at,
    mf.transcript_compensation_attempts = mt.compensation_attempts,
    mf.transcript_retry_count = mt.retry_count;

-- 3. 删除拆分表
DROP TABLE IF EXISTS media_ai_analysis;
DROP TABLE IF EXISTS media_transcription;

COMMIT;
