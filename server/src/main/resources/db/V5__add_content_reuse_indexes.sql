-- ============================================================
-- V5: 内容复用优化索引
-- 目的：优化 ContentTaskGate 重构中锁内 DB 反查性能
-- 影响：selectCompletedAnalysisByMd5 / selectCompletedTranscriptByMd5
-- ============================================================

USE media_db;

-- 覆盖索引：file_md5 + ai_status + id
-- 用途：按 MD5 反查已完成分析的记录，避免回表
-- 受益查询：MediaFileMapper.selectCompletedAnalysisByMd5
CREATE INDEX idx_md5_ai_status ON media_files(file_md5, ai_status, id);

-- 覆盖索引：file_md5 + transcript_status + id
-- 用途：按 MD5 反查已完成转写的记录，避免回表
-- 受益查询：MediaFileMapper.selectCompletedTranscriptByMd5
CREATE INDEX idx_md5_transcript_status ON media_files(file_md5, transcript_status, id);
