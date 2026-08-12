-- ============================================================
-- AI 分析 / 文字提取状态字段迁移脚本
-- 为 media_files 表新增 ai_status 与 transcript_status 两个字段
-- 目的：将「状态」从 ai_summary / transcript_text 文案中剥离，
--       改为独立字段表达，前端按状态判断而非字符串匹配
-- 注意：如果字段已存在会报错，执行前请先确认
-- ============================================================

-- 新增 AI 分析状态字段
ALTER TABLE media_files
    ADD COLUMN ai_status VARCHAR(32) DEFAULT 'NONE' COMMENT 'AI分析状态: NONE/PENDING/PROCESSING/SUCCESS/FAILED';

-- 新增文字提取状态字段
ALTER TABLE media_files
    ADD COLUMN transcript_status VARCHAR(32) DEFAULT 'NONE' COMMENT '文字提取状态: NONE/PROCESSING/SUCCESS/FAILED';

-- ============================================================
-- 回填历史数据（让已分析过的老数据拥有正确状态，前端无需兼容判断）
-- ============================================================

-- 1. AI 分析：含 Markdown 标题 "##" 视为成功
UPDATE media_files SET ai_status = 'SUCCESS'
 WHERE ai_summary IS NOT NULL AND ai_summary LIKE '%##%';

-- 2. AI 分析：含错误标志视为失败（注意放在 SUCCESS 之后，避免覆盖）
UPDATE media_files SET ai_status = 'FAILED'
 WHERE ai_summary IS NOT NULL
   AND ai_status = 'NONE'
   AND (ai_summary LIKE '%❌%' OR ai_summary LIKE '%failed%' OR ai_summary LIKE '%失败%');

-- 3. 文字提取：含错误前缀视为失败
UPDATE media_files SET transcript_status = 'FAILED'
 WHERE transcript_text IS NOT NULL
   AND (transcript_text LIKE '❌%' OR transcript_text LIKE 'FFmpeg 转换失败%' OR transcript_text LIKE '处理异常%');

-- 4. 文字提取：其余非空文本视为成功
UPDATE media_files SET transcript_status = 'SUCCESS'
 WHERE transcript_text IS NOT NULL
   AND transcript_text <> ''
   AND transcript_status = 'NONE';
