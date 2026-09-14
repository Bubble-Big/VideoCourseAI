-- V7: 新增用户手动重试计数器，解决补偿调度器与用户重试的计数冲突
-- 问题：用户手动重试清零 compensationAttempts 后，补偿调度器基于旧快照递增计数
-- 方案：新增 analysis_retry_count 字段，补偿调度器更新前检查 analysis_retry_count 是否变化

ALTER TABLE media_file ADD COLUMN analysis_retry_count INT NOT NULL DEFAULT 0 COMMENT 'AI分析用户手动重试次数（用于检测补偿调度器计数冲突）';
