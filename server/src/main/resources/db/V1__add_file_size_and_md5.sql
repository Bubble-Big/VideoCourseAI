-- ============================================================
-- 分片上传重构：数据库迁移脚本
-- 为已有 media_files 表新增 file_size 和 file_md5 字段
-- 注意：如果字段已存在会报错，执行前请先确认
-- ============================================================

-- 新增文件大小字段
ALTER TABLE media_files
    ADD COLUMN file_size BIGINT DEFAULT NULL COMMENT '文件大小(字节)';

-- 新增全文件MD5字段
ALTER TABLE media_files
    ADD COLUMN file_md5 VARCHAR(32) DEFAULT NULL COMMENT '全文件MD5哈希(后端合并后计算)';

-- 为去重查询添加联合索引
CREATE INDEX idx_media_files_user_md5 ON media_files(user_id, file_md5);
