-- ============================================================
-- VideoCourseAI 数据库建表脚本
-- 数据库: media_db (需提前创建)
-- 适用: MySQL 8.0+
-- ============================================================

CREATE DATABASE IF NOT EXISTS media_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE media_db;

-- ============================================================
-- 用户表
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username VARCHAR(64)  NOT NULL COMMENT '用户名',
    password VARCHAR(128) NOT NULL COMMENT '密码(明文存储，待改进)',
    nickname VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
    avatar   VARCHAR(512) DEFAULT NULL COMMENT '头像URL',
    role     VARCHAR(32)  DEFAULT 'USER' COMMENT '角色: USER / ADMIN',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ============================================================
-- 媒体文件表（含分片上传重构新增字段）
-- ============================================================
CREATE TABLE IF NOT EXISTS media_files (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '媒体文件ID',
    user_id         BIGINT       DEFAULT NULL COMMENT '上传者ID',
    filename        VARCHAR(512) NOT NULL COMMENT '原始文件名',
    status          VARCHAR(32)  DEFAULT 'UPLOADED' COMMENT '状态: UPLOADED / COMPLETED',
    file_path       VARCHAR(1024) DEFAULT NULL COMMENT 'MinIO 公开访问URL',
    file_size       BIGINT       DEFAULT NULL COMMENT '文件大小(字节) — 分片上传重构新增',
    file_md5        VARCHAR(32)  DEFAULT NULL COMMENT '全文件MD5哈希(后端合并后计算) — 分片上传重构新增',
    ai_summary      TEXT         DEFAULT NULL COMMENT 'AI 智能总结(Markdown)',
    transcript_text TEXT         DEFAULT NULL COMMENT '语音转写全文',
    cover_url       VARCHAR(1024) DEFAULT NULL COMMENT '封面URL',
    upload_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间(数据库自动填充)',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_user_md5 (user_id, file_md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='媒体文件表';
