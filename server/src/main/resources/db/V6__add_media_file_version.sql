-- 问题 1：增加乐观锁版本号，防止补偿调度器与原任务写入竞态覆盖
ALTER TABLE media_files
  ADD COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（防补偿调度器与原任务写入竞态覆盖）';

-- 问题 5：增加补偿调度器专属重试计数，与用户手动重试（ai_attempts）语义分离
ALTER TABLE media_files
  ADD COLUMN compensation_attempts INT NOT NULL DEFAULT 0 COMMENT '补偿调度器专属重试计数，不受用户手动重新提交影响';

-- 问题 8：补充索引，避免 selectStalledAnalysis 全表扫描
CREATE INDEX idx_ai_status_process_at ON media_files(ai_status, ai_process_at, id);
