-- ============================================================
-- AI 分析补偿式重试字段迁移脚本
-- 为 media_files 表新增 ai_process_at 与 ai_attempts 两个字段
-- 目的：支持「DB 状态机 + 定时补偿」重试（取代 RocketMQ reconsumeTimes 重投）
-- 注意：如果字段已存在会报错，执行前请先确认
-- ============================================================

ALTER TABLE media_files
    ADD COLUMN ai_process_at DATETIME NULL COMMENT '最近一次 AI 分析尝试时间（提交/开始/失败时刷新）';

ALTER TABLE media_files
    ADD COLUMN ai_attempts INT NOT NULL DEFAULT 0 COMMENT 'AI 分析已尝试次数（重试上限判定）';

-- ============================================================
-- 回填历史数据：遗留的 PENDING/PROCESSING 记录补时间戳，
-- 避免 ai_process_at 为 NULL 被补偿查询（ai_process_at < threshold）漏扫
-- ============================================================
UPDATE media_files SET ai_process_at = NOW()
 WHERE ai_status IN ('PENDING','PROCESSING') AND ai_process_at IS NULL;
