# 数据库表拆分方案 — media_files 表垂直拆分重构

> 计划编号：DB-SPLIT-001  
> 创建日期：2026-09-15  
> 状态：📋 待评审

---

## 一、问题诊断

### 1.1 当前 media_files 表字段统计

**字段总数：21 个**（含实体类字段）

| 分类 | 字段 | 类型 | 说明 |
|------|------|------|------|
| **主键/归属** | id, user_id | BIGINT | 核心标识 |
| **文件元信息** | filename, status, file_path, file_size, file_md5, cover_url, upload_time | VARCHAR/BIGINT/DATETIME | 基础文件属性（8字段） |
| **AI分析域** | ai_status, ai_summary, ai_process_at, ai_attempts, compensation_attempts, analysis_retry_count | VARCHAR/TEXT/DATETIME/INT | AI分析状态+结果+补偿重试（6字段） |
| **转写域** | transcript_status, transcript_text, transcript_process_at, transcript_compensation_attempts, transcript_retry_count | VARCHAR/TEXT/DATETIME/INT | 转写状态+结果+补偿重试（5字段） |
| **并发控制** | version | INT | 乐观锁版本号 |

### 1.2 核心问题

#### 问题 1：职责混杂（Single Responsibility Violation）
单表同时承载 **3 个独立领域**：
- 文件元信息（上传完成即固化）
- AI 分析链路（异步处理 + 补偿重试）
- 转写链路（异步处理 + 补偿重试）

#### 问题 2：TEXT 字段膨胀影响查询性能
```sql
-- 列表查询（MediaController.list）只需要元信息，但被迫扫描整行
SELECT * FROM media_files WHERE user_id = ? ORDER BY upload_time DESC;
-- ❌ 携带 ai_summary (TEXT) 和 transcript_text (TEXT)，每行可能数 KB
-- ❌ 页面缓存（Redis）存储大量无用数据
-- ❌ 网络传输浪费
```

#### 问题 3：查询模式不匹配

| 查询场景 | 当前实现 | 需要字段 | 冗余字段 |
|----------|----------|----------|----------|
| **列表查询** | `SELECT *` | id, filename, status, upload_time, ai_status, transcript_status | ai_summary (TEXT), transcript_text (TEXT) |
| **详情查询** | `SELECT *` | 全部字段 | 无 |
| **补偿调度器（AI）** | `selectStalledAnalysis` | id, ai_status, ai_process_at, compensation_attempts | filename, file_path, ai_summary, transcript_* |
| **补偿调度器（转写）** | `selectStalledTranscription` | id, transcript_status, transcript_process_at, transcript_compensation_attempts | filename, file_path, transcript_text, ai_* |

#### 问题 4：索引利用率低
```sql
-- 补偿调度器扫描 PROCESSING 记录
SELECT * FROM media_files 
WHERE ai_status IN ('PENDING','PROCESSING') 
  AND ai_process_at < ? 
ORDER BY ai_process_at ASC LIMIT 10;

-- ❌ 索引只能覆盖 WHERE/ORDER BY，SELECT * 回表取 TEXT 字段
-- ❌ 两个补偿调度器（AI/转写）共用一张表，缓存污染
```

#### 问题 5：数据膨胀趋势
- 假设单个视频：
  - `ai_summary`：2KB（Markdown）
  - `transcript_text`：10KB（长视频转写）
  - 单行 ≈ 12KB
- 10 万条记录 → **1.2GB** 表空间
- 列表分页查询（`LIMIT 20`）→ 扫描 **240KB**，实际只需 **2KB** 元信息

---

## 二、拆分方案设计

### 2.1 垂直拆分策略（按领域职责）

```
原表：media_files (21 字段)
         ↓
拆分为 3 张表：

┌─────────────────────┐
│   media_files       │  核心文件表（轻量）
│  ─────────────────  │
│  id (PK)            │
│  user_id            │
│  filename           │
│  status             │
│  file_path          │
│  file_size          │
│  file_md5           │
│  cover_url          │
│  upload_time        │
│  version (乐观锁)    │
└─────────────────────┘
         │ 1
         │
         ├───────────────────────┐
         │ 1                     │ 1
         ↓                       ↓
┌──────────────────────┐  ┌──────────────────────┐
│ media_ai_analysis    │  │ media_transcription  │
│ ─────────────────    │  │ ─────────────────    │
│ id (PK)              │  │ id (PK)              │
│ media_id (FK) UNIQUE │  │ media_id (FK) UNIQUE │
│ status               │  │ status               │
│ summary (TEXT)       │  │ transcript_text (TEXT)│
│ process_at           │  │ process_at           │
│ attempts             │  │ attempts             │
│ compensation_attempts│  │ compensation_attempts│
│ retry_count          │  │ retry_count          │
│ created_at           │  │ created_at           │
│ updated_at           │  │ updated_at           │
└──────────────────────┘  └──────────────────────┘
```

### 2.2 新表结构 DDL

#### 表 1：media_files（核心文件表）
```sql
CREATE TABLE IF NOT EXISTS media_files (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '媒体文件ID',
    user_id         BIGINT       DEFAULT NULL COMMENT '上传者ID',
    filename        VARCHAR(512) NOT NULL COMMENT '原始文件名',
    status          VARCHAR(32)  DEFAULT 'UPLOADED' COMMENT '文件状态: UPLOADED / COMPLETED',
    file_path       VARCHAR(1024) DEFAULT NULL COMMENT 'MinIO 公开访问URL',
    file_size       BIGINT       DEFAULT NULL COMMENT '文件大小(字节)',
    file_md5        VARCHAR(32)  DEFAULT NULL COMMENT '全文件MD5哈希',
    cover_url       VARCHAR(1024) DEFAULT NULL COMMENT '封面URL',
    upload_time     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    KEY idx_user_id (user_id),
    KEY idx_user_md5 (user_id, file_md5),
    KEY idx_file_md5 (file_md5),
    KEY idx_upload_time (upload_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='媒体文件核心表（轻量元信息）';
```

#### 表 2：media_ai_analysis（AI 分析表）
```sql
CREATE TABLE IF NOT EXISTS media_ai_analysis (
    id                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '分析记录ID',
    media_id                BIGINT       NOT NULL COMMENT '关联 media_files.id',
    status                  VARCHAR(32)  DEFAULT 'NONE' COMMENT 'AI分析状态: NONE/PENDING/PROCESSING/SUCCESS/FAILED',
    summary                 TEXT         DEFAULT NULL COMMENT 'AI 智能总结(Markdown)',
    process_at              DATETIME     DEFAULT NULL COMMENT '最近一次处理尝试时间',
    attempts                INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数（用户手动重试 + MQ 重投）',
    compensation_attempts   INT          NOT NULL DEFAULT 0 COMMENT '补偿调度器专属重试计数',
    retry_count             INT          NOT NULL DEFAULT 0 COMMENT '用户手动重试计数器',
    created_at              DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    updated_at              DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_id (media_id),
    KEY idx_status_process (status, process_at) COMMENT '补偿调度器查询索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 分析状态与结果表';
```

#### 表 3：media_transcription（转写表）
```sql
CREATE TABLE IF NOT EXISTS media_transcription (
    id                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '转写记录ID',
    media_id                BIGINT       NOT NULL COMMENT '关联 media_files.id',
    status                  VARCHAR(32)  DEFAULT 'NONE' COMMENT '转写状态: NONE/PROCESSING/SUCCESS/FAILED',
    transcript_text         TEXT         DEFAULT NULL COMMENT '语音转写全文',
    process_at              DATETIME     DEFAULT NULL COMMENT '最近一次处理尝试时间',
    attempts                INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    compensation_attempts   INT          NOT NULL DEFAULT 0 COMMENT '补偿调度器专属重试计数',
    retry_count             INT          NOT NULL DEFAULT 0 COMMENT '用户手动重试计数器',
    created_at              DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    updated_at              DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_id (media_id),
    KEY idx_status_process (status, process_at) COMMENT '补偿调度器查询索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='语音转写状态与结果表';
```

### 2.3 拆分收益量化

| 指标 | 拆分前 | 拆分后 | 改善 |
|------|--------|--------|------|
| **列表查询行大小** | ~12KB (含2个TEXT) | ~200B (纯元信息) | **98%↓** |
| **列表分页(20条)扫描** | 240KB | 4KB | **98%↓** |
| **补偿调度器扫描(10条)** | 120KB | 1KB (只含状态字段) | **99%↓** |
| **Redis缓存空间(列表)** | 240KB/页 | 4KB/页 | **98%↓** |
| **索引覆盖率** | 0% (回表取TEXT) | 100% (状态表索引覆盖) | **质变** |

---

## 三、数据迁移脚本

### 3.1 迁移 SQL（V10__split_media_files_table.sql）

```sql
-- ============================================================
-- V10: media_files 表垂直拆分迁移
-- 执行前备份: mysqldump -u root -p media_db > backup_before_split.sql
-- ============================================================

USE media_db;

-- Step 1: 创建新表
-- (使用上面 2.2 节的 DDL)

-- Step 2: 数据迁移 —— 保留原表字段，拆分数据到新表
START TRANSACTION;

-- 2.1 迁移 AI 分析数据
INSERT INTO media_ai_analysis (
    media_id, status, summary, process_at, attempts, 
    compensation_attempts, retry_count, created_at, updated_at
)
SELECT 
    id AS media_id,
    COALESCE(ai_status, 'NONE') AS status,
    ai_summary AS summary,
    ai_process_at AS process_at,
    COALESCE(ai_attempts, 0) AS attempts,
    COALESCE(compensation_attempts, 0) AS compensation_attempts,
    COALESCE(analysis_retry_count, 0) AS retry_count,
    upload_time AS created_at,
    NOW() AS updated_at
FROM media_files;

-- 2.2 迁移转写数据
INSERT INTO media_transcription (
    media_id, status, transcript_text, process_at, attempts,
    compensation_attempts, retry_count, created_at, updated_at
)
SELECT 
    id AS media_id,
    COALESCE(transcript_status, 'NONE') AS status,
    transcript_text AS transcript_text,
    transcript_process_at AS process_at,
    0 AS attempts,  -- 原表无转写 attempts 字段，默认0
    COALESCE(transcript_compensation_attempts, 0) AS compensation_attempts,
    COALESCE(transcript_retry_count, 0) AS retry_count,
    upload_time AS created_at,
    NOW() AS updated_at
FROM media_files;

-- 2.3 保留原表核心字段（DROP COLUMN 方式，保留数据）
-- ⚠️ 生产环境建议分步执行，先验证迁移正确性再删除列
ALTER TABLE media_files
    DROP COLUMN ai_status,
    DROP COLUMN ai_summary,
    DROP COLUMN ai_process_at,
    DROP COLUMN ai_attempts,
    DROP COLUMN compensation_attempts,
    DROP COLUMN analysis_retry_count,
    DROP COLUMN transcript_status,
    DROP COLUMN transcript_text,
    DROP COLUMN transcript_process_at,
    DROP COLUMN transcript_compensation_attempts,
    DROP COLUMN transcript_retry_count;

COMMIT;

-- Step 3: 验证数据一致性
SELECT 
    (SELECT COUNT(*) FROM media_files) AS files_count,
    (SELECT COUNT(*) FROM media_ai_analysis) AS analysis_count,
    (SELECT COUNT(*) FROM media_transcription) AS transcription_count;
-- 预期：三个计数相等

-- Step 4: 添加索引优化（已在建表DDL中包含）
```

### 3.2 回滚脚本（rollback_v10.sql）

```sql
-- ⚠️ 紧急回滚方案（需提前备份）
USE media_db;

START TRANSACTION;

-- 1. 恢复被删除的列
ALTER TABLE media_files
    ADD COLUMN ai_status VARCHAR(32) DEFAULT 'NONE' COMMENT 'AI分析状态',
    ADD COLUMN ai_summary TEXT DEFAULT NULL COMMENT 'AI总结',
    ADD COLUMN ai_process_at DATETIME DEFAULT NULL COMMENT 'AI处理时间',
    ADD COLUMN ai_attempts INT DEFAULT 0 COMMENT 'AI尝试次数',
    ADD COLUMN compensation_attempts INT DEFAULT 0 COMMENT '补偿重试次数',
    ADD COLUMN analysis_retry_count INT DEFAULT 0 COMMENT '用户重试次数',
    ADD COLUMN transcript_status VARCHAR(32) DEFAULT 'NONE' COMMENT '转写状态',
    ADD COLUMN transcript_text TEXT DEFAULT NULL COMMENT '转写文本',
    ADD COLUMN transcript_process_at DATETIME DEFAULT NULL COMMENT '转写处理时间',
    ADD COLUMN transcript_compensation_attempts INT DEFAULT 0 COMMENT '转写补偿重试',
    ADD COLUMN transcript_retry_count INT DEFAULT 0 COMMENT '转写用户重试';

-- 2. 回填数据
UPDATE media_files mf
LEFT JOIN media_ai_analysis ma ON mf.id = ma.media_id
LEFT JOIN media_transcription mt ON mf.id = mt.media_id
SET 
    mf.ai_status = ma.status,
    mf.ai_summary = ma.summary,
    mf.ai_process_at = ma.process_at,
    mf.ai_attempts = ma.attempts,
    mf.compensation_attempts = ma.compensation_attempts,
    mf.analysis_retry_count = ma.retry_count,
    mf.transcript_status = mt.status,
    mf.transcript_text = mt.transcript_text,
    mf.transcript_process_at = mt.process_at,
    mf.transcript_compensation_attempts = mt.compensation_attempts,
    mf.transcript_retry_count = mt.retry_count;

-- 3. 删除拆分表
DROP TABLE IF EXISTS media_ai_analysis;
DROP TABLE IF EXISTS media_transcription;

COMMIT;
```

---

## 四、代码重构方案

### 4.1 实体类重构

#### 4.1.1 MediaFile.java（保留核心字段）
```java
package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("media_files")
public class MediaFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String filename;
    private String status;        // UPLOADED, COMPLETED
    private String filePath;
    private Long fileSize;
    private String fileMd5;
    private String coverUrl;
    private LocalDateTime uploadTime;

    @Version
    private Integer version;      // 乐观锁版本号

    // ❌ 删除：所有 AI 分析相关字段
    // ❌ 删除：所有转写相关字段
}
```

#### 4.1.2 MediaAiAnalysis.java（新增）
```java
package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("media_ai_analysis")
public class MediaAiAnalysis {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long mediaId;         // 关联 media_files.id
    private String status;        // NONE/PENDING/PROCESSING/SUCCESS/FAILED
    private String summary;       // TEXT
    private LocalDateTime processAt;
    private Integer attempts;
    private Integer compensationAttempts;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

#### 4.1.3 MediaTranscription.java（新增）
```java
package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("media_transcription")
public class MediaTranscription {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long mediaId;         // 关联 media_files.id
    private String status;        // NONE/PROCESSING/SUCCESS/FAILED
    private String transcriptText; // TEXT
    private LocalDateTime processAt;
    private Integer attempts;
    private Integer compensationAttempts;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### 4.2 Mapper 层重构

#### 4.2.1 MediaFileMapper.java（简化）
```java
@Mapper
public interface MediaFileMapper extends BaseMapper<MediaFile> {
    
    // ❌ 删除：selectCompletedAnalysisByMd5（迁移到 MediaAiAnalysisMapper）
    // ❌ 删除：selectCompletedTranscriptByMd5（迁移到 MediaTranscriptionMapper）
    // ❌ 删除：selectStalledAnalysis（迁移到 MediaAiAnalysisMapper）
    // ❌ 删除：selectStalledTranscription（迁移到 MediaTranscriptionMapper）
}
```

#### 4.2.2 MediaAiAnalysisMapper.java（新增）
```java
@Mapper
public interface MediaAiAnalysisMapper extends BaseMapper<MediaAiAnalysis> {

    /**
     * 按内容 MD5 反查一条已成功分析的记录（归属复用）
     */
    @Select("SELECT ma.* FROM media_ai_analysis ma " +
            "JOIN media_files mf ON ma.media_id = mf.id " +
            "WHERE mf.file_md5 = #{md5} AND ma.status = 'SUCCESS' " +
            "AND ma.summary IS NOT NULL AND ma.summary <> '' AND ma.media_id <> #{excludeMediaId} " +
            "ORDER BY ma.id DESC LIMIT 1")
    MediaAiAnalysis selectCompletedAnalysisByMd5(@Param("md5") String md5, 
                                                  @Param("excludeMediaId") Long excludeMediaId);

    /**
     * 查「卡死」的 AI 分析记录（补偿调度器专用）
     */
    @Select("SELECT * FROM media_ai_analysis " +
            "WHERE status IN ('PENDING','PROCESSING') " +
            "AND process_at < #{threshold} " +
            "ORDER BY process_at ASC LIMIT #{limit}")
    List<MediaAiAnalysis> selectStalledAnalysis(@Param("threshold") LocalDateTime threshold,
                                                 @Param("limit") int limit);
}
```

#### 4.2.3 MediaTranscriptionMapper.java（新增）
```java
@Mapper
public interface MediaTranscriptionMapper extends BaseMapper<MediaTranscription> {

    /**
     * 按内容 MD5 反查一条已成功转写的记录（归属复用）
     */
    @Select("SELECT mt.* FROM media_transcription mt " +
            "JOIN media_files mf ON mt.media_id = mf.id " +
            "WHERE mf.file_md5 = #{md5} AND mt.status = 'SUCCESS' " +
            "AND mt.transcript_text IS NOT NULL AND mt.transcript_text <> '' " +
            "AND mt.media_id <> #{excludeMediaId} " +
            "ORDER BY mt.id DESC LIMIT 1")
    MediaTranscription selectCompletedTranscriptByMd5(@Param("md5") String md5,
                                                      @Param("excludeMediaId") Long excludeMediaId);

    /**
     * 查「卡死」的转写记录（补偿调度器专用）
     */
    @Select("SELECT * FROM media_transcription " +
            "WHERE status = 'PROCESSING' " +
            "AND process_at < #{threshold} " +
            "ORDER BY process_at ASC LIMIT #{limit}")
    List<MediaTranscription> selectStalledTranscription(@Param("threshold") LocalDateTime threshold,
                                                        @Param("limit") int limit);
}
```

### 4.3 Service 层重构

#### 4.3.1 AiService.java 重构要点

**核心变更：**
1. 注入 `MediaAiAnalysisMapper` 和 `MediaTranscriptionMapper`
2. 所有状态更新操作改为操作子表：
   - `mediaFile.setAiStatus()` → `aiAnalysis.setStatus()`
   - `mediaFile.setAiSummary()` → `aiAnalysis.setSummary()`
   - `mediaFileMapper.update()` → `aiAnalysisMapper.updateById()` + `transcriptionMapper.updateById()`

**示例代码片段：**
```java
@Service
public class AiService {

    private final MediaFileMapper mediaFileMapper;
    private final MediaAiAnalysisMapper aiAnalysisMapper;       // 新增
    private final MediaTranscriptionMapper transcriptionMapper; // 新增
    // ... 其他依赖

    @Async("aiTaskExecutor")
    public CompletableFuture<GateOutcome> asyncAnalyze(Long mediaId, Boolean force) {
        // 1. 查询文件元信息（轻量）
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) {
            throw new AiAnalysisException("文件不存在: " + mediaId, false, AiFailStage.FILE);
        }

        // 2. 查询或初始化 AI 分析记录
        MediaAiAnalysis aiAnalysis = aiAnalysisMapper.selectOne(
            new LambdaQueryWrapper<MediaAiAnalysis>().eq(MediaAiAnalysis::getMediaId, mediaId)
        );
        if (aiAnalysis == null) {
            aiAnalysis = new MediaAiAnalysis();
            aiAnalysis.setMediaId(mediaId);
            aiAnalysis.setStatus(AiStatus.PENDING.name());
            aiAnalysisMapper.insert(aiAnalysis);
        }

        // 3. 结果复用逻辑（查子表）
        if (!Boolean.TRUE.equals(force)) {
            String contentHash = mediaService.contentHash(mediaId);
            MediaAiAnalysis reusable = aiAnalysisMapper.selectCompletedAnalysisByMd5(
                mediaFile.getFileMd5(), mediaId
            );
            if (reusable != null) {
                // 复用：回填到当前记录
                aiAnalysis.setStatus(AiStatus.SUCCESS.name());
                aiAnalysis.setSummary(reusable.getSummary());
                aiAnalysis.setProcessAt(LocalDateTime.now());
                aiAnalysisMapper.updateById(aiAnalysis);
                return CompletableFuture.completedFuture(GateOutcome.REUSE);
            }
        }

        // 4. 进入处理态（更新子表）
        aiAnalysis.setStatus(AiStatus.PROCESSING.name());
        aiAnalysis.setProcessAt(LocalDateTime.now());
        aiAnalysisMapper.updateById(aiAnalysis);

        try {
            // 5. 调用转写（操作 MediaTranscription 表）
            String text = transcribeWithReuse(mediaFile, force);
            
            // 6. 调用 LLM 生成总结
            String summary = aiAnalysisStrategy.generateSummaryFromText(text);
            
            // 7. 落库成功（更新子表）
            aiAnalysis.setStatus(AiStatus.SUCCESS.name());
            aiAnalysis.setSummary(summary);
            aiAnalysis.setProcessAt(LocalDateTime.now());
            aiAnalysisMapper.updateById(aiAnalysis);
            
            return CompletableFuture.completedFuture(GateOutcome.PROCEED);

        } catch (Exception e) {
            // 8. 异常处理（更新子表）
            handleAnalysisException(aiAnalysis, mediaId, e);
            return CompletableFuture.completedFuture(GateOutcome.PROCEED);
        }
    }

    private String transcribeWithReuse(MediaFile mediaFile, Boolean force) {
        // 1. 查询或初始化转写记录
        MediaTranscription transcription = transcriptionMapper.selectOne(
            new LambdaQueryWrapper<MediaTranscription>()
                .eq(MediaTranscription::getMediaId, mediaFile.getId())
        );
        if (transcription == null) {
            transcription = new MediaTranscription();
            transcription.setMediaId(mediaFile.getId());
            transcription.setStatus(AiStatus.PROCESSING.name());
            transcriptionMapper.insert(transcription);
        }

        // 2. 复用逻辑（查子表）
        if (!Boolean.TRUE.equals(force)) {
            MediaTranscription reusable = transcriptionMapper.selectCompletedTranscriptByMd5(
                mediaFile.getFileMd5(), mediaFile.getId()
            );
            if (reusable != null) {
                transcription.setStatus(AiStatus.SUCCESS.name());
                transcription.setTranscriptText(reusable.getTranscriptText());
                transcription.setProcessAt(LocalDateTime.now());
                transcriptionMapper.updateById(transcription);
                return reusable.getTranscriptText();
            }
        }

        // 3. 真正转写
        String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
        transcription.setStatus(AiStatus.SUCCESS.name());
        transcription.setTranscriptText(text);
        transcription.setProcessAt(LocalDateTime.now());
        transcriptionMapper.updateById(transcription);
        return text;
    }
}
```

#### 4.3.2 ContentTaskGate.java 重构要点

**核心变更：**
- `resolveAnalysis(MediaFile file, String contentHash)` → 先查 `MediaAiAnalysis` 判断是否已有结果
- `resolveTranscript(MediaFile file, String contentHash)` → 先查 `MediaTranscription` 判断是否已有结果

#### 4.3.3 补偿调度器重构

**AnalysisCompensationScheduler.java：**
```java
@Override
protected List<MediaFile> scanStalledTasks(LocalDateTime threshold, int limit) {
    // ❌ 旧实现：mediaFileMapper.selectStalledAnalysis(threshold, limit)
    
    // ✅ 新实现：先查子表，再关联主表
    List<MediaAiAnalysis> stalledList = aiAnalysisMapper.selectStalledAnalysis(threshold, limit);
    
    // 批量查询关联的 MediaFile（避免 N+1）
    if (stalledList.isEmpty()) {
        return Collections.emptyList();
    }
    List<Long> mediaIds = stalledList.stream()
        .map(MediaAiAnalysis::getMediaId)
        .collect(Collectors.toList());
    return mediaFileMapper.selectBatchIds(mediaIds);
}
```

**TranscriptionCompensationScheduler.java：** 同理调整。

### 4.4 Controller 层重构

#### 4.4.1 MediaController.list() 重构

**旧实现（冗余查询）：**
```java
@GetMapping("/list")
public Result<List<MediaFile>> list(@RequestParam(required = false) Long userId) {
    // ❌ SELECT * FROM media_files WHERE user_id = ? → 携带 TEXT 字段
    List<MediaFile> files = mediaFileMapper.selectList(
        new LambdaQueryWrapper<MediaFile>().eq(MediaFile::getUserId, userId)
    );
    return Result.ok(files);
}
```

**新实现（按需关联）：**
```java
@GetMapping("/list")
public Result<List<MediaFileVO>> list(@RequestParam(required = false) Long userId) {
    // 1. 只查主表（轻量）
    List<MediaFile> files = mediaFileMapper.selectList(
        new LambdaQueryWrapper<MediaFile>()
            .eq(userId != null, MediaFile::getUserId, userId)
            .orderByDesc(MediaFile::getUploadTime)
    );
    
    // 2. 批量查询状态（避免 N+1）
    List<Long> mediaIds = files.stream().map(MediaFile::getId).collect(Collectors.toList());
    List<MediaAiAnalysis> analysisList = aiAnalysisMapper.selectBatchIds(mediaIds);
    List<MediaTranscription> transcriptionList = transcriptionMapper.selectBatchIds(mediaIds);
    
    // 3. 组装 VO（只返回状态字段，不返回 TEXT 内容）
    Map<Long, String> aiStatusMap = analysisList.stream()
        .collect(Collectors.toMap(MediaAiAnalysis::getMediaId, MediaAiAnalysis::getStatus));
    Map<Long, String> transcriptStatusMap = transcriptionList.stream()
        .collect(Collectors.toMap(MediaTranscription::getMediaId, MediaTranscription::getStatus));
    
    List<MediaFileVO> voList = files.stream().map(file -> {
        MediaFileVO vo = new MediaFileVO();
        BeanUtils.copyProperties(file, vo);
        vo.setAiStatus(aiStatusMap.getOrDefault(file.getId(), "NONE"));
        vo.setTranscriptStatus(transcriptStatusMap.getOrDefault(file.getId(), "NONE"));
        return vo;
    }).collect(Collectors.toList());
    
    return Result.ok(voList);
}
```

#### 4.4.2 DebugController.getAnalysisDetail() 新增详情接口

```java
/**
 * 获取 AI 分析详情（按需加载 TEXT 字段）
 */
@GetMapping("/analysis-detail")
public Result<MediaAiAnalysis> getAnalysisDetail(@RequestParam Long mediaId) {
    MediaAiAnalysis analysis = aiAnalysisMapper.selectOne(
        new LambdaQueryWrapper<MediaAiAnalysis>().eq(MediaAiAnalysis::getMediaId, mediaId)
    );
    if (analysis == null) {
        return Result.error(ErrorCode.NOT_FOUND, "分析记录不存在");
    }
    return Result.ok(analysis);
}

/**
 * 获取转写详情（按需加载 TEXT 字段）
 */
@GetMapping("/transcription-detail")
public Result<MediaTranscription> getTranscriptionDetail(@RequestParam Long mediaId) {
    MediaTranscription transcription = transcriptionMapper.selectOne(
        new LambdaQueryWrapper<MediaTranscription>().eq(MediaTranscription::getMediaId, mediaId)
    );
    if (transcription == null) {
        return Result.error(ErrorCode.NOT_FOUND, "转写记录不存在");
    }
    return Result.ok(transcription);
}
```

### 4.5 VO 类新增

```java
package com.example.server.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 媒体文件列表VO（不含TEXT字段）
 */
@Data
public class MediaFileVO {
    private Long id;
    private Long userId;
    private String filename;
    private String status;
    private String filePath;
    private Long fileSize;
    private String fileMd5;
    private String coverUrl;
    private LocalDateTime uploadTime;
    
    // 关联状态（仅状态枚举，不含内容）
    private String aiStatus;
    private String transcriptStatus;
}
```

---

## 五、影响范围评估

### 5.1 需要修改的文件清单

| 文件路径 | 修改类型 | 工作量 |
|----------|----------|--------|
| **Entity 层** |
| `MediaFile.java` | 删除 AI/转写字段 | 🟢 简单 |
| `MediaAiAnalysis.java` | 新增实体类 | 🟢 简单 |
| `MediaTranscription.java` | 新增实体类 | 🟢 简单 |
| **Mapper 层** |
| `MediaFileMapper.java` | 删除 4 个查询方法 | 🟢 简单 |
| `MediaAiAnalysisMapper.java` | 新增 Mapper | 🟢 简单 |
| `MediaTranscriptionMapper.java` | 新增 Mapper | 🟢 简单 |
| **Service 层** |
| `AiService.java` | 重写所有状态更新逻辑 | 🔴 复杂（约 300 行） |
| `ContentTaskGate.java` | 调整归属复用逻辑 | 🟡 中等（约 100 行） |
| `AnalysisCompensationScheduler.java` | 调整扫描逻辑 | 🟡 中等（约 50 行） |
| `TranscriptionCompensationScheduler.java` | 调整扫描逻辑 | 🟡 中等（约 50 行） |
| `MediaService.java` | 无需修改（不涉及状态字段） | ✅ 无变更 |
| **Controller 层** |
| `MediaController.java` | list() 改为 JOIN 查询 | 🟡 中等（约 50 行） |
| `DebugController.java` | 调整状态读取逻辑 | 🟡 中等（约 30 行） |
| **DTO/VO 层** |
| `MediaFileVO.java` | 新增列表VO类 | 🟢 简单 |
| **测试类** |
| `AiServiceTest.java` | 重写所有测试用例 | 🔴 复杂（约 200 行） |
| `ContentTaskGateTest.java` | 调整 Mock 对象 | 🟡 中等（约 100 行） |
| **数据库** |
| `V10__split_media_files_table.sql` | 迁移脚本 | 🔴 复杂（需充分测试） |

**预估工作量：** 8-12 小时（含测试验证）

### 5.2 兼容性风险

#### 风险 1：Redis 缓存键失效
- **问题**：列表查询缓存 Key 为 `media:list:user:{userId}`，缓存内容从 `List<MediaFile>` 变为 `List<MediaFileVO>`
- **解决方案**：迁移时强制失效所有列表缓存 `redisTemplate.delete("media:list:user:*")`

#### 风险 2：前端依赖字段缺失
- **问题**：前端可能直接读取 `mediaFile.aiStatus` / `mediaFile.transcriptStatus`
- **解决方案**：保持 API 响应格式不变（通过 VO 类提供相同字段结构）

#### 风险 3：乐观锁失效
- **问题**：原表使用 `version` 字段防止并发覆盖，拆分后子表无乐观锁
- **解决方案**：
  - 主表保留 `version`（防止文件元信息并发覆盖）
  - 子表使用 `updated_at` 字段 + `WHERE` 条件防止覆盖：
    ```java
    aiAnalysisMapper.update(null, new LambdaUpdateWrapper<MediaAiAnalysis>()
        .eq(MediaAiAnalysis::getMediaId, mediaId)
        .eq(MediaAiAnalysis::getUpdatedAt, oldUpdatedAt)  // 乐观锁替代
        .set(MediaAiAnalysis::getStatus, newStatus)
        .set(MediaAiAnalysis::getUpdatedAt, LocalDateTime.now()));
    ```

#### 风险 4：事务边界
- **问题**：原单表更新隐式事务，拆分后跨表更新需显式事务
- **解决方案**：关键方法加 `@Transactional` 注解：
  ```java
  @Transactional(rollbackFor = Exception.class)
  public void updateAnalysisAndTranscription(Long mediaId, String summary, String text) {
      aiAnalysisMapper.update(...);
      transcriptionMapper.update(...);
  }
  ```

---

## 六、实施计划

### 6.1 分阶段执行（降低风险）

#### 阶段 1：准备阶段（1-2小时）
- [ ] 备份生产数据库：`mysqldump -u root -p media_db > backup_$(date +%Y%m%d).sql`
- [ ] 创建新表（在测试环境验证 DDL）
- [ ] 准备回滚脚本并测试

#### 阶段 2：数据迁移（1小时）
- [ ] 执行 `V10__split_media_files_table.sql` 迁移脚本
- [ ] 验证数据一致性：
  ```sql
  -- 检查记录数一致
  SELECT COUNT(*) FROM media_files;          -- N
  SELECT COUNT(*) FROM media_ai_analysis;    -- N
  SELECT COUNT(*) FROM media_transcription;  -- N
  
  -- 抽查几条记录对比原表备份
  SELECT * FROM media_files WHERE id = 1;
  SELECT * FROM media_ai_analysis WHERE media_id = 1;
  SELECT * FROM media_transcription WHERE media_id = 1;
  ```
- [ ] 验证索引生效：
  ```sql
  EXPLAIN SELECT * FROM media_ai_analysis 
  WHERE status = 'PROCESSING' AND process_at < NOW() - INTERVAL 20 MINUTE;
  -- 预期：key = idx_status_process (索引覆盖)
  ```

#### 阶段 3：代码重构（4-6小时）
- [ ] 按 4.1-4.4 节顺序重构代码
- [ ] 编译通过：`mvn clean compile`
- [ ] 单元测试全部通过：`mvn test`

#### 阶段 4：集成测试（2-3小时）
- [ ] 启动项目：`/start-dev`
- [ ] 测试完整链路：`/analyze-video`
- [ ] 测试列表查询：前端访问列表页，验证无 TEXT 字段泄漏
- [ ] 测试详情查询：点击详情，验证 AI 总结和转写内容正常显示
- [ ] 测试补偿调度器：手动触发卡死任务，观察日志

#### 阶段 5：性能验证（1小时）
- [ ] 对比拆分前后列表查询 SQL 执行时间：
  ```sql
  -- 拆分前
  SELECT * FROM media_files_backup WHERE user_id = 1 LIMIT 20;
  -- 预期：~50ms（10万行数据）
  
  -- 拆分后
  SELECT mf.*, ma.status AS ai_status, mt.status AS transcript_status
  FROM media_files mf
  LEFT JOIN media_ai_analysis ma ON mf.id = ma.media_id
  LEFT JOIN media_transcription mt ON mf.id = mt.media_id
  WHERE mf.user_id = 1
  LIMIT 20;
  -- 预期：~5ms（索引覆盖 + 无 TEXT 扫描）
  ```
- [ ] Redis 缓存大小对比：
  ```bash
  # 拆分前
  redis-cli --raw get "media:list:user:1" | wc -c  # 约 240KB
  
  # 拆分后
  redis-cli --raw get "media:list:user:1" | wc -c  # 约 4KB
  ```

#### 阶段 6：灰度发布（可选）
- [ ] 部署到预发布环境，小流量验证 1-2 天
- [ ] 监控错误日志、SQL 慢查询、Redis 命中率
- [ ] 无异常后全量发布

---

## 七、监控与回滚预案

### 7.1 关键监控指标

| 指标 | 目标值 | 告警阈值 |
|------|--------|----------|
| 列表查询 P99 延迟 | < 100ms | > 500ms |
| 详情查询 P99 延迟 | < 200ms | > 1s |
| Redis 缓存命中率 | > 95% | < 80% |
| SQL 慢查询数量 | 0 | > 10/分钟 |
| 补偿调度器扫描耗时 | < 500ms | > 2s |

### 7.2 回滚决策树

```
发现问题
    ↓
数据完整性问题？
    ├─ 是 → 立即回滚（执行 rollback_v10.sql）
    └─ 否
         ↓
    性能下降 > 50%？
         ├─ 是 → 回滚并优化索引
         └─ 否
              ↓
         功能异常但无数据丢失？
              └─ 热修复代码（发布补丁版本）
```

### 7.3 回滚 SOP

1. **暂停写入**：临时下线上传接口 `POST /media/upload-url` 和 `POST /chunk/complete`
2. **执行回滚 SQL**：`mysql -u root -p media_db < rollback_v10.sql`
3. **验证数据**：抽查 10 条记录，对比备份文件
4. **重启服务**：回滚到上一个稳定版本代码
5. **恢复写入**：上线接口，观察 1 小时

---

## 八、后续优化方向

### 8.1 分表分库（百万级数据）
- **水平拆分**：按 `user_id` 分 8 库 × 16 表 = 128 张表
- **路由规则**：`hash(user_id) % 128`
- **适用场景**：单表超过 500 万行

### 8.2 ES 索引（全文检索）
- 将 `transcript_text` 同步到 Elasticsearch
- 支持关键词搜索：「包含"机器学习"的视频」
- 倒排索引加速模糊查询

### 8.3 OSS 归档（冷数据）
- 超过 6 个月未访问的 `ai_summary` / `transcript_text`
- 迁移到 MinIO 归档桶或 S3 Glacier
- DB 只保留状态字段 + OSS 对象 Key

---

## 九、总结

### 9.1 拆分必要性评分

| 维度 | 评分 | 说明 |
|------|------|------|
| **性能收益** | ⭐⭐⭐⭐⭐ | 列表查询从 240KB → 4KB，质变提升 |
| **维护性** | ⭐⭐⭐⭐ | 领域隔离，单一职责 |
| **扩展性** | ⭐⭐⭐⭐⭐ | 为分表分库、ES 索引铺路 |
| **实施风险** | ⭐⭐⭐ | 中等（需充分测试） |
| **工作量** | ⭐⭐⭐ | 8-12 小时 |

**综合评分：⭐⭐⭐⭐（强烈推荐）**

### 9.2 关键决策点

1. **是否需要拆分？** ✅ 是 —— TEXT 字段污染列表查询，查询模式不匹配
2. **拆分策略？** ✅ 垂直拆分（按领域） —— 比水平拆分（按用户）优先级更高
3. **拆几张表？** ✅ 3 张 —— `media_files` + `media_ai_analysis` + `media_transcription`
4. **是否保留原表？** ✅ 否 —— 删除冗余列，避免双写
5. **是否需要VO类？** ✅ 是 —— 保持前端API兼容性

### 9.3 下一步行动

- [ ] **评审会议**：技术团队评审本方案（预计 1 小时）
- [ ] **风险评估**：DBA 评审迁移脚本和回滚方案
- [ ] **排期**：排入下一个迭代（预计 2 个工作日）
- [ ] **执行**：按 6.1 节分阶段执行
- [ ] **复盘**：迁移完成后总结经验，更新文档

---

**文档作者**：Claude (Sonnet 5)  
**审核状态**：待技术评审  
**相关文档**：[ARCHITECTURE.md](../ARCHITECTURE.md), [AI_ANALYSIS_COMPENSATION_HARDENING_PLAN.md](./AI_ANALYSIS_COMPENSATION_HARDENING_PLAN.md)
