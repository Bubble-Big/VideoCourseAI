-- 文字提取补偿调度器字段
ALTER TABLE media_files
ADD COLUMN transcript_compensation_attempts INT NOT NULL DEFAULT 0
COMMENT '文字提取补偿调度器重试计数';

ALTER TABLE media_files
ADD COLUMN transcript_retry_count INT NOT NULL DEFAULT 0
COMMENT '用户文字提取手动重试次数（用于检测补偿调度器计数冲突）';

-- 新增索引：加速补偿调度器扫描
CREATE INDEX idx_transcript_status_process_at
ON media_files(transcript_status, ai_process_at);
