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
-- 媒体文件表（V10 表拆分后：仅保留基础信息）
-- ============================================================
CREATE TABLE IF NOT EXISTS media_files (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '媒体文件ID',
    user_id         BIGINT       DEFAULT NULL COMMENT '上传者ID',
    filename        VARCHAR(512) NOT NULL COMMENT '原始文件名',
    status          VARCHAR(32)  DEFAULT 'UPLOADED' COMMENT '状态: UPLOADED / COMPLETED',
    file_path       VARCHAR(1024) DEFAULT NULL COMMENT 'MinIO 公开访问URL',
    file_size       BIGINT       DEFAULT NULL COMMENT '文件大小(字节)',
    file_md5        VARCHAR(32)  DEFAULT NULL COMMENT '全文件MD5哈希(内容级去重)',
    cover_url       VARCHAR(1024) DEFAULT NULL COMMENT '封面URL',
    upload_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_user_md5 (user_id, file_md5),
    KEY idx_file_md5 (file_md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='媒体文件表（基础信息）';

-- ============================================================
-- AI 分析子表（V10 新增）
-- ============================================================
CREATE TABLE IF NOT EXISTS media_ai_analysis (
    id                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '分析记录ID',
    media_id                BIGINT       NOT NULL COMMENT '关联 media_files.id',
    status                  VARCHAR(32)  DEFAULT 'NONE' COMMENT '分析状态: NONE/PENDING/PROCESSING/SUCCESS/FAILED',
    summary                 TEXT         DEFAULT NULL COMMENT 'AI 智能总结(Markdown)',
    process_at              DATETIME     DEFAULT NULL COMMENT '最近一次分析尝试时间',
    attempts                INT          NOT NULL DEFAULT 0 COMMENT '实时任务尝试次数',
    compensation_attempts   INT          NOT NULL DEFAULT 0 COMMENT '补偿调度器尝试次数',
    retry_count             INT          NOT NULL DEFAULT 0 COMMENT '用户手动重试计数（冲突检测）',
    created_at              DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at              DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_id (media_id),
    KEY idx_stalled (status, process_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 分析子表';

-- ============================================================
-- 文字转写子表（V10 新增）
-- ============================================================
CREATE TABLE IF NOT EXISTS media_transcription (
    id                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '转写记录ID',
    media_id                BIGINT       NOT NULL COMMENT '关联 media_files.id',
    status                  VARCHAR(32)  DEFAULT 'NONE' COMMENT '转写状态: NONE/PENDING/PROCESSING/SUCCESS/FAILED',
    transcript_text         TEXT         DEFAULT NULL COMMENT '语音转写全文',
    process_at              DATETIME     DEFAULT NULL COMMENT '最近一次转写尝试时间',
    attempts                INT          NOT NULL DEFAULT 0 COMMENT '实时任务尝试次数',
    compensation_attempts   INT          NOT NULL DEFAULT 0 COMMENT '补偿调度器尝试次数',
    retry_count             INT          NOT NULL DEFAULT 0 COMMENT '用户手动重试计数（冲突检测）',
    created_at              DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at              DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_id (media_id),
    KEY idx_stalled (status, process_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文字转写子表';

-- ============================================================
-- AI 分析失败台账表
-- ============================================================
CREATE TABLE IF NOT EXISTS failed_analysis_task (
    id         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '失败记录ID',
    media_id   BIGINT        NOT NULL COMMENT '关联 media_files.id',
    error_type VARCHAR(64)   DEFAULT NULL COMMENT '异常类型（AiAnalysisException/Exception 等）',
    error_msg  VARCHAR(2000) DEFAULT NULL COMMENT '错误摘要（受控，不含堆栈）',
    attempts   INT           DEFAULT 1 COMMENT '累计投递次数',
    created_at DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '首次失败时间',
    PRIMARY KEY (id),
    KEY idx_media_id (media_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 分析失败台账';
