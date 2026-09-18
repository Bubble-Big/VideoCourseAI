# 补偿调度器子表适配修复方案

## 问题概述

**现象**：数据库表拆分后，前端无法正确调取已分析完毕的视频文本，总是重新调用 API。

**根因**：`AbstractCompensationScheduler.compensateOne()` 方法仍尝试更新父表 `media_files` 中已移除的字段（`ai_process_at`、`transcript_process_at`），导致生成无 `SET` 子句的错误 SQL，补偿逻辑失效，任务永久卡在 `PROCESSING` 状态。

**影响范围**：
- AI 分析任务（`media_ai_analysis` 表）
- 文字转写任务（`media_transcription` 表）

## 错误日志证据

来自 `IDEAoutput.txt`（2026-09-17 20:22:36）：

```
### Error updating database.  Cause: java.sql.SQLSyntaxErrorException: 
You have an error in your SQL syntax; check the manual that corresponds to your MySQL server version 
for the right syntax to use near 'WHERE (id = 69 AND version = 0)' at line 1

### The error may exist in com/example/server/mapper/MediaFileMapper.java (best guess)
### The error may involve com.example.server.mapper.MediaFileMapper.update-Inline
### The error occurred while setting parameters
### SQL: UPDATE media_files WHERE (id = 69 AND version = 0)
```

**关键信息**：
- mediaId=69 卡在 `PROCESSING` 状态超过 50 分钟
- `process_at` 时间戳：2026-09-17 19:29:51
- SQL 缺少 `SET` 子句，仅有 `WHERE` 条件

## 架构演进回顾

### 1️⃣ 表拆分前（V8 之前）

`media_files` 表包含所有字段：

```sql
CREATE TABLE media_files (
    id BIGINT PRIMARY KEY,
    -- ... 基础字段 ...
    ai_status VARCHAR(20),
    ai_summary TEXT,
    ai_process_at DATETIME,
    transcript_status VARCHAR(20),
    transcript_text TEXT,
    transcript_process_at DATETIME,
    version INT DEFAULT 0
);
```

补偿调度器直接更新父表：

```java
// AbstractCompensationScheduler.compensateOne()
LambdaUpdateWrapper<MediaFile> wrapper = new LambdaUpdateWrapper<MediaFile>()
    .eq(MediaFile::getId, f.getId())
    .eq(MediaFile::getVersion, f.getVersion());
refreshProcessAtField(wrapper, LocalDateTime.now());  // ✅ 设置 ai_process_at/transcript_process_at
int updated = mediaFileMapper.update(null, wrapper);   // ✅ 生成正确 SQL：UPDATE media_files SET ai_process_at=? WHERE ...
```

### 2️⃣ 表拆分后（V9，2026-09-16）

父表 `media_files` 仅保留核心字段：

```sql
CREATE TABLE media_files (
    id BIGINT PRIMARY KEY,
    user_id BIGINT,
    filename VARCHAR(500),
    status VARCHAR(20),
    file_path VARCHAR(1000),
    file_size BIGINT,
    file_md5 VARCHAR(32),
    cover_url VARCHAR(1000),
    upload_time DATETIME,
    version INT DEFAULT 0
);
```

子表 `media_ai_analysis` 存储 AI 分析数据：

```sql
CREATE TABLE media_ai_analysis (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    media_id BIGINT UNIQUE NOT NULL,
    status VARCHAR(20) DEFAULT 'NONE',
    summary TEXT,
    process_at DATETIME,
    version INT DEFAULT 0,
    FOREIGN KEY (media_id) REFERENCES media_files(id) ON DELETE CASCADE
);
```

子表 `media_transcription` 存储转写数据：

```sql
CREATE TABLE media_transcription (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    media_id BIGINT UNIQUE NOT NULL,
    status VARCHAR(20) DEFAULT 'NONE',
    text TEXT,
    process_at DATETIME,
    version INT DEFAULT 0,
    FOREIGN KEY (media_id) REFERENCES media_files(id) ON DELETE CASCADE
);
```

### 3️⃣ 代码未同步（Bug 根源）

**AbstractCompensationScheduler.compensateOne()** 仍操作父表：

```java
// 第 129-150 行（问题代码）
protected void compensateOne(MediaFile f) {
    LambdaUpdateWrapper<MediaFile> wrapper = new LambdaUpdateWrapper<MediaFile>()
        .eq(MediaFile::getId, f.getId())
        .eq(MediaFile::getVersion, f.getVersion());
    
    refreshProcessAtField(wrapper, LocalDateTime.now());  // ❌ 子类实现为空
    
    int updated = mediaFileMapper.update(null, wrapper);  // ❌ 生成错误 SQL：UPDATE media_files WHERE ...
    
    if (updated == 0) {
        log.info("[Compensation] Optimistic lock failed: id={}", f.getId());
        return;
    }
    
    submitTask(f.getId());
}
```

**AnalysisCompensationScheduler.refreshProcessAtField()** 空实现：

```java
// 第 146-149 行
@Override
protected void refreshProcessAtField(LambdaUpdateWrapper<MediaFile> wrapper, LocalDateTime time) {
    // 这个方法已废弃，改为直接操作子表
    // 保留空实现以兼容抽象基类  // ❌ 导致 wrapper 无 SET 字段
}
```

**TranscriptionCompensationScheduler.refreshProcessAtField()** 同样为空。

## 修复方案

### 核心思路

**彻底重构补偿调度器**，从"更新父表 + 提交任务"模式转为"更新子表 + 提交任务"模式：

1. 抽象基类 `AbstractCompensationScheduler` 改为泛型设计，支持操作任意子表实体
2. 子类 `AnalysisCompensationScheduler` 操作 `MediaAiAnalysis` 表
3. 子类 `TranscriptionCompensationScheduler` 操作 `MediaTranscription` 表
4. 移除对父表 `media_files` 的直接更新依赖

### 实施步骤

#### 步骤 1：重构抽象基类

**文件**：`server/src/main/java/com/example/server/service/AbstractCompensationScheduler.java`

**改动点 1.1**：引入子表 Mapper 抽象

```java
public abstract class AbstractCompensationScheduler<T> {  // 泛型化
    
    @Resource
    protected MediaFileMapper mediaFileMapper;  // 保留，用于查询 media_id 列表
    
    // 新增：子类提供子表 Mapper
    protected abstract BaseMapper<T> getChildTableMapper();
    
    // 新增：子类提供查询 stale 记录的 LambdaQueryWrapper
    protected abstract LambdaQueryWrapper<T> buildStaleQuery(LocalDateTime staleThreshold);
    
    // 新增：子类提供更新 process_at 的 LambdaUpdateWrapper
    protected abstract LambdaUpdateWrapper<T> buildProcessAtUpdate(T entity, LocalDateTime newTime);
    
    // 新增：子类从子表实体中提取 mediaId
    protected abstract Long getMediaId(T entity);
    
    // 新增：子类从子表实体中提取 version
    protected abstract Integer getVersion(T entity);
}
```

**改动点 1.2**：重构 `findStaleTasks()` 方法

```java
// 第 66-99 行（原逻辑）删除，替换为：
@Scheduled(fixedRate = 60000)
public void findStaleTasks() {
    LocalDateTime staleThreshold = LocalDateTime.now().minusMinutes(getStaledMinutes());
    
    // 直接查询子表中的 stale 记录
    LambdaQueryWrapper<T> query = buildStaleQuery(staleThreshold);
    List<T> staleRecords = getChildTableMapper().selectList(query);
    
    if (staleRecords.isEmpty()) {
        return;
    }
    
    log.info("[Compensation-{}] Found {} stale tasks", getTaskType(), staleRecords.size());
    
    for (T record : staleRecords) {
        try {
            compensateOne(record);
        } catch (Exception e) {
            log.error("[Compensation-{}] Error: mediaId={}", 
                getTaskType(), getMediaId(record), e);
        }
    }
}
```

**改动点 1.3**：重构 `compensateOne()` 方法

```java
// 第 129-150 行（原逻辑）删除，替换为：
protected void compensateOne(T entity) {
    Long mediaId = getMediaId(entity);
    Integer version = getVersion(entity);
    
    // 构建子表更新 wrapper（带乐观锁）
    LambdaUpdateWrapper<T> wrapper = buildProcessAtUpdate(entity, LocalDateTime.now());
    
    int updated = getChildTableMapper().update(null, wrapper);
    
    if (updated == 0) {
        log.info("[Compensation-{}] Optimistic lock failed: mediaId={}", 
            getTaskType(), mediaId);
        return;
    }
    
    log.info("[Compensation-{}] Refreshed process_at: mediaId={}", 
        getTaskType(), mediaId);
    
    submitTask(mediaId);
}
```

**改动点 1.4**：移除废弃方法

```java
// 删除以下方法：
// protected abstract void refreshProcessAtField(LambdaUpdateWrapper<MediaFile> wrapper, LocalDateTime time);
```

#### 步骤 2：重构 AI 分析补偿调度器

**文件**：`server/src/main/java/com/example/server/service/AnalysisCompensationScheduler.java`

**完整重写**：

```java
@Service
@Slf4j
public class AnalysisCompensationScheduler extends AbstractCompensationScheduler<MediaAiAnalysis> {
    
    @Resource
    private MediaAiAnalysisMapper mediaAiAnalysisMapper;
    
    @Resource
    private AiService aiService;
    
    @Override
    protected BaseMapper<MediaAiAnalysis> getChildTableMapper() {
        return mediaAiAnalysisMapper;
    }
    
    @Override
    protected LambdaQueryWrapper<MediaAiAnalysis> buildStaleQuery(LocalDateTime staleThreshold) {
        return new LambdaQueryWrapper<MediaAiAnalysis>()
            .eq(MediaAiAnalysis::getStatus, "PROCESSING")
            .lt(MediaAiAnalysis::getProcessAt, staleThreshold);
    }
    
    @Override
    protected LambdaUpdateWrapper<MediaAiAnalysis> buildProcessAtUpdate(MediaAiAnalysis entity, LocalDateTime newTime) {
        return new LambdaUpdateWrapper<MediaAiAnalysis>()
            .eq(MediaAiAnalysis::getMediaId, entity.getMediaId())
            .eq(MediaAiAnalysis::getVersion, entity.getVersion())
            .set(MediaAiAnalysis::getProcessAt, newTime);
    }
    
    @Override
    protected Long getMediaId(MediaAiAnalysis entity) {
        return entity.getMediaId();
    }
    
    @Override
    protected Integer getVersion(MediaAiAnalysis entity) {
        return entity.getVersion();
    }
    
    @Override
    protected String getTaskType() {
        return "AI";
    }
    
    @Override
    protected int getStaledMinutes() {
        return 5;
    }
    
    @Override
    protected void submitTask(Long mediaId) {
        aiService.asyncAnalyze(mediaId, false);
    }
}
```

#### 步骤 3：重构文字转写补偿调度器

**文件**：`server/src/main/java/com/example/server/service/TranscriptionCompensationScheduler.java`

**完整重写**：

```java
@Service
@Slf4j
public class TranscriptionCompensationScheduler extends AbstractCompensationScheduler<MediaTranscription> {
    
    @Resource
    private MediaTranscriptionMapper mediaTranscriptionMapper;
    
    @Resource
    private AiService aiService;
    
    @Override
    protected BaseMapper<MediaTranscription> getChildTableMapper() {
        return mediaTranscriptionMapper;
    }
    
    @Override
    protected LambdaQueryWrapper<MediaTranscription> buildStaleQuery(LocalDateTime staleThreshold) {
        return new LambdaQueryWrapper<MediaTranscription>()
            .eq(MediaTranscription::getStatus, "PROCESSING")
            .lt(MediaTranscription::getProcessAt, staleThreshold);
    }
    
    @Override
    protected LambdaUpdateWrapper<MediaTranscription> buildProcessAtUpdate(MediaTranscription entity, LocalDateTime newTime) {
        return new LambdaUpdateWrapper<MediaTranscription>()
            .eq(MediaTranscription::getMediaId, entity.getMediaId())
            .eq(MediaTranscription::getVersion, entity.getVersion())
            .set(MediaTranscription::getProcessAt, newTime);
    }
    
    @Override
    protected Long getMediaId(MediaTranscription entity) {
        return entity.getMediaId();
    }
    
    @Override
    protected Integer getVersion(MediaTranscription entity) {
        return entity.getVersion();
    }
    
    @Override
    protected String getTaskType() {
        return "Transcription";
    }
    
    @Override
    protected int getStaledMinutes() {
        return 5;
    }
    
    @Override
    protected void submitTask(Long mediaId) {
        aiService.asyncTranscribe(mediaId, false);
    }
}
```

#### 步骤 4：补充 MediaController.list() 子表关联查询（遗漏项）

**问题**：`DATABASE_TABLE_SPLIT_PLAN.md` 显示此接口未正确重构。

**文件**：`server/src/main/java/com/example/server/controller/MediaController.java`

**当前代码**（第 187-228 行）：

```java
List<MediaFile> files = mediaFileMapper.selectList(
    new LambdaQueryWrapper<MediaFile>()
        .eq(MediaFile::getUserId, userId)
        .orderByDesc(MediaFile::getUploadTime)
);

// ❌ 批量查询子表但未正确映射
List<Long> mediaIds = files.stream().map(MediaFile::getId).collect(Collectors.toList());
List<MediaAiAnalysis> analysisList = aiAnalysisMapper.selectBatchIds(mediaIds);
List<MediaTranscription> transcriptionList = transcriptionMapper.selectBatchIds(mediaIds);
```

**问题**：`selectBatchIds()` 查询的是主键 `id`，而非外键 `media_id`，导致状态字段始终为 `NONE`。

**修复代码**：

```java
// 第 197-207 行替换为：
List<Long> mediaIds = files.stream().map(MediaFile::getId).collect(Collectors.toList());

// ✅ 使用外键 media_id 批量查询
List<MediaAiAnalysis> analysisList = aiAnalysisMapper.selectList(
    new LambdaQueryWrapper<MediaAiAnalysis>().in(MediaAiAnalysis::getMediaId, mediaIds)
);
List<MediaTranscription> transcriptionList = transcriptionMapper.selectList(
    new LambdaQueryWrapper<MediaTranscription>().in(MediaTranscription::getMediaId, mediaIds)
);
```

## 验证步骤

### 1. 编译验证

```bash
cd server
mvn clean compile
```

**预期**：无编译错误。

### 2. 单元测试（可选）

创建测试类 `CompensationSchedulerTest`：

```java
@SpringBootTest
class CompensationSchedulerTest {
    
    @Resource
    private MediaAiAnalysisMapper aiAnalysisMapper;
    
    @Resource
    private AnalysisCompensationScheduler scheduler;
    
    @Test
    void testBuildStaleQuery() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        LambdaQueryWrapper<MediaAiAnalysis> query = scheduler.buildStaleQuery(threshold);
        
        List<MediaAiAnalysis> stale = aiAnalysisMapper.selectList(query);
        System.out.println("Stale tasks: " + stale.size());
    }
}
```

### 3. 集成测试

#### 3.1 准备测试数据

在数据库中手动插入一条 stale 记录：

```sql
-- 插入父表记录
INSERT INTO media_files (id, user_id, filename, status, upload_time, version)
VALUES (999, 1, 'test.mp4', 'COMPLETED', NOW(), 0);

-- 插入 stale 的 AI 分析记录
INSERT INTO media_ai_analysis (media_id, status, process_at, version)
VALUES (999, 'PROCESSING', DATE_SUB(NOW(), INTERVAL 10 MINUTE), 0);
```

#### 3.2 启动项目

```bash
# 使用一键启动脚本
/start-dev
```

#### 3.3 观察日志

等待 1 分钟后，查看控制台输出：

```
✅ 预期日志：
[Compensation-AI] Found 1 stale tasks
[Compensation-AI] Refreshed process_at: mediaId=999
[AiService] Starting AI analysis: mediaId=999
```

```
❌ 错误日志（修复前）：
### Error updating database.  Cause: java.sql.SQLSyntaxErrorException: 
You have an error in your SQL syntax; ... near 'WHERE (id = 999 AND version = 0)'
```

#### 3.4 验证数据库

```sql
-- 检查 process_at 是否更新
SELECT media_id, status, process_at, version 
FROM media_ai_analysis 
WHERE media_id = 999;
```

**预期**：
- `process_at` 更新为当前时间
- `version` 递增为 1（乐观锁生效）

### 4. 前端验证

#### 4.1 访问工作台

浏览器打开 `http://localhost:5173`，上传一个视频文件。

#### 4.2 触发 AI 分析

点击视频卡片的"AI 分析"按钮，等待任务完成（侧边栏显示"✅ 任务完成"）。

#### 4.3 关闭侧边栏后重新点击

**预期行为**：
- 侧边栏直接显示已有的分析结果
- 控制台日志：`[SSE] 推送历史内容: mediaId=XXX`
- **不应**再次调用 `aiAnalyze` API

#### 4.4 检查 Redux DevTools（如果已安装）

```javascript
// state.media.list[0]
{
  id: 123,
  aiStatus: "SUCCESS",  // ✅ 正确状态
  transcriptStatus: "SUCCESS"
}
```

## 风险评估

| 风险项 | 影响 | 缓解措施 |
|-------|------|---------|
| 泛型化破坏现有继承关系 | 中 | 编译时即可发现，修复成本低 |
| 乐观锁竞争导致补偿失败 | 低 | 下一轮调度（1 分钟后）重试 |
| 子表查询性能下降 | 低 | `media_id` 和 `status` 已建复合索引 |
| 历史数据缺少 `version` 字段 | 中 | V9 迁移脚本已设置默认值 0 |

## 关联文档

- [DATABASE_TABLE_SPLIT_PLAN.md](./DATABASE_TABLE_SPLIT_PLAN.md)：表拆分设计方案
- [COMPENSATION_SCHEDULER_REFACTOR_PLAN.md](./COMPENSATION_SCHEDULER_REFACTOR_PLAN.md)：补偿调度器重构历史
- [ARCHITECTURE.md](../ARCHITECTURE.md)：项目整体架构说明

## 执行计划

| 步骤 | 预计耗时 | 责任人 | 状态 |
|-----|---------|--------|------|
| 重构 `AbstractCompensationScheduler` | 30 分钟 | 开发 | ⏳ 待开始 |
| 重构 `AnalysisCompensationScheduler` | 15 分钟 | 开发 | ⏳ 待开始 |
| 重构 `TranscriptionCompensationScheduler` | 15 分钟 | 开发 | ⏳ 待开始 |
| 修复 `MediaController.list()` | 10 分钟 | 开发 | ⏳ 待开始 |
| 编写单元测试 | 20 分钟 | 开发 | ⏳ 待开始 |
| 集成测试 | 15 分钟 | QA | ⏳ 待开始 |
| 前端验证 | 10 分钟 | QA | ⏳ 待开始 |
| 代码审查 | 15 分钟 | Tech Lead | ⏳ 待开始 |
| 部署上线 | 5 分钟 | DevOps | ⏳ 待开始 |

**总计**：约 2.5 小时

---

**文档版本**：v1.0  
**创建时间**：2026-09-17  
**最后更新**：2026-09-17  
**相关 Issue**：前端无法正确调取已完成任务的历史文本（mediaId=69 卡死案例）
