-- 添加文字提取处理时间戳字段（用于补偿调度器扫描卡死任务）
ALTER TABLE media_files
ADD COLUMN transcript_process_at DATETIME NULL
COMMENT '文字提取最近处理时间（PROCESSING 状态下更新，供补偿调度器扫描）';

-- 修正 V8 创建的错误索引（原索引引用了错误的字段）
DROP INDEX idx_transcript_status_process_at ON media_files;

-- 重建正确的索引
CREATE INDEX idx_transcript_status_process_at
ON media_files(transcript_status, transcript_process_at);
