# 视频分片上传 + 断点续传重构计划

## 一、对用户构想的评审

### ✅ 合理且保留的设计

| 设计点 | 评价 |
|--------|------|
| **5MB 固定切片** | 合理。平衡请求次数与重传代价，1000 片可覆盖 5GB 视频 |
| **uploadId + chunkIndex + totalChunks 协议** | 标准做法，与 S3 Multipart Upload、tus.io 一致 |
| **check 接口做状态查询，返回已传分片集合** | 核心设计正确，前端过滤后实现真正的断点续传 |
| **并发上传分片** | 合理，需控制并发数（建议 3 个）避免浏览器连接数耗尽 |
| **合并时分布式锁 + 幂等检查** | 正确的防重复合并方案 |
| **前端集齐后发起合并请求** | 职责清晰 |

### ⚠️ 需要调整的设计

1. **uploadId 应由后端生成，而非前端携带**
   - 用户构想：前端携带 uploadId（可被伪造）
   - 修正方案：前端在首次发起 `init` 请求时，后端生成 UUID 作为 uploadId 返回；后续所有请求携带此后端签发的 uploadId

2. **去重策略分层设计（前端不做全文件 MD5）**
   - 用户构想：用 fileName + fileSize 判断重复
   - 问题：不同视频可能有相同的文件名和大小；但如果让前端计算 GB 级文件的全量 MD5，会阻塞数分钟，体验不可接受
   - 修正方案（两层）：
     - **轻量层（init 时）**：后端用 `(userId, fileName, fileSize)` 做启发式匹配，命中则提示"可能存在同名同大小文件"，由用户判断
     - **精确层（合并后）**：后端合并分片后直接从 MinIO 流式计算全文件 MD5，存入 DB。此后同一文件再次上传时，后端在 init 阶段就能精确去重（"秒传"）

3. **"先落盘后记账"应细化为三阶段**
   - 用户构想：MinIO 成功 → Redis 记录
   - 问题：如果 MinIO 成功后 Redis 写入失败，分片成为"孤儿"（存在 MinIO 但合并时不知道）
   - 修正方案：① Redis 标记 `PENDING` → ② 上传 MinIO → ③ Redis 标记 `COMPLETED`；合并时只看 COMPLETED 集合

4. **分片合并必须使用 MinIO `composeObject`（服务端合并）**
   - 用户构想未明确合并方式
   - 关键约束：绝不能下载所有分片到 Spring Boot 服务器再合并上传，那会导致内存崩溃
   - 正确方案：使用 MinIO SDK 的 `composeObject` API，在 MinIO 服务端拼接，零下载带宽

### ➕ 遗漏的设计点

| 遗漏点 | 说明 |
|--------|------|
| **全文件 MD5 由后端计算** | 前端不做全文件 MD5（GB 级文件太慢）。后端合并分片后从 MinIO 流式计算，速度快且不消耗浏览器资源 |
| **分片级 MD5 校验** | 每个分片上传时前端携带分片 MD5，后端接收后校验，防止传输损坏 |
| **过期分片清理** | 用户中断上传后，MinIO 中的分片对象永久残留，需要定时清理任务（48 小时过期） |
| **上传进度查询接口** | 前端需要独立的进度查询能力，不依赖本地状态 |
| **取消上传接口** | 用户主动放弃上传时，清理 MinIO + Redis |
| **beforeunload 提醒** | 上传中关闭标签页时提醒用户 |
| **大文件秒传** | 如果 fileMd5 已存在且完成上传，直接返回已有记录 |

---

## 二、架构设计要点

### 2.1 新增/修改的接口

所有接口在 `/media/api/chunk` 路径下：

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/media/api/chunk/init` | 初始化上传，返回 uploadId（后端生成），去重检测 |
| `POST` | `/media/api/chunk/check` | 查询上传状态，返回已完成分片序号集合 |
| `POST` | `/media/api/chunk/upload` | 上传单个分片（multipart），含分片 MD5 校验 |
| `POST` | `/media/api/chunk/merge` | 发起合并（分布式锁 + 幂等检查） |
| `DELETE` | `/media/api/chunk/cancel` | 取消上传，清理 MinIO + Redis |

### 2.2 Redis Key 设计

| Key 模式 | 类型 | 内容 | TTL |
|----------|------|------|-----|
| `upload:meta:{uploadId}` | Hash | fileName, fileSize, totalChunks, userId, status, createdAt | 48h |
| `upload:chunks:{uploadId}` | Set | 已成功上传的分片序号（如 "0","1","5"） | 48h |
| `upload:chunk:md5:{uploadId}` | Hash | field=分片序号, value=分片MD5 | 48h |
| `lock:merge:{uploadId}` | RLock | Redisson 分布式锁，leaseTime=120s | — |

### 2.3 MinIO 存储结构变更

```
media/                            (现有 bucket)
  chunks/                         (新增：分片临时存储)
    {uploadId}/
      0, 1, 2, ...               (分片对象，序号为名)
  {uuid}.mp4                      (现有：最终合并文件，保持不变)
```

### 2.4 数据库变更

`media_files` 表新增两个字段：

```sql
ALTER TABLE media_files
    ADD COLUMN file_size BIGINT DEFAULT NULL COMMENT '文件大小(字节)',
    ADD COLUMN file_md5 VARCHAR(32) DEFAULT NULL COMMENT '全文件MD5哈希';
```

对应 `MediaFile.java` 实体新增 `fileSize`、`fileMd5` 字段。

### 2.5 关键决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 合并策略 | MinIO `composeObject` | 服务端合并，零下载带宽，最多 1000 源对象 |
| 前端并发数 | 3 | 浏览器同域最多 6 连接，留 3 给其他请求 |
| 过期时间 | 48 小时 | 跨周末可恢复，过期自动清理 |
| 分片大小上限 | 5MB × 10000 = 50GB | 合法上限，超出拒绝 |
| 全文件 MD5 | 后端合并后计算 | GB 级文件前端计算太慢（数分钟），后端从 MinIO 流式计算仅需秒级 |
| 分片 MD5 | 前端每片计算 | 5MB 分片哈希 ~50ms，随上传自然计算，用于传输完整性校验 |
| init 去重 | `(userId, fileName, fileSize)` 轻量提示 | 不依赖前端全文件哈希，给用户知情权但不强制拦截 |

---

## 三、重构任务清单

### 阶段一：后端基础设施

#### 任务 1.1：数据库表扩展
- 执行 SQL 为 `media_files` 表新增 `file_size`（BIGINT）、`file_md5`（VARCHAR(32)）字段
- 更新 `MediaFile.java` 实体，新增对应属性
- **验证**：编译通过，`SELECT` 确认新字段存在

#### 任务 1.2：MinioUtils 扩展
- 在 `MinioUtils.java` 中新增方法：
  - `uploadChunkObject(uploadId, chunkIndex, inputStream, size)` — 上传分片到 `chunks/{uploadId}/{chunkIndex}`
  - `composeObjects(uploadId, totalChunks, targetObjectName)` — 调用 `composeObject` 合并分片
  - `deleteChunkObjects(uploadId)` — 批量删除指定 uploadId 的所有分片对象
  - `listChunkObjects(uploadId)` — 列出指定 uploadId 的分片对象
- **验证**：单元测试上传 2 个分片 → composeObject 合并 → 校验合并文件正确 → 清理

#### 任务 1.3：创建 ChunkUploadService
- 新建 `service/ChunkUploadService.java`，封装所有分片业务逻辑：
  - `initUpload(InitRequest)` — 生成 uploadId → Redis 写入 meta Hash → 去重检测
  - `checkStatus(uploadId)` — 查询 Redis 返回状态 + 已完成分片集合
  - `uploadChunk(uploadId, chunkIndex, inputStream, chunkMd5)` — 校验 → MinIO 落盘 → Redis SADD
  - `mergeChunks(uploadId, userId)` — 分布式锁 → 幂等检查 → composeObject → MySQL 写入 → 清理
  - `cancelUpload(uploadId)` — 清理 MinIO 分片 + Redis 所有相关 key
- **验证**：Service 编译通过，逻辑完整

### 阶段二：后端接口与集成

#### 任务 2.1：创建 ChunkController
- 新建 `controller/ChunkController.java`（或在 MediaController 中扩展），5 个端点
- 构造器注入模式（与现有 Controller 一致）
- 统一请求/响应格式（JSON body）
- **验证**：用 curl 逐个调用接口，init → upload × N → check → merge，确认全链路

#### 任务 2.2：合并接口的分布式锁与幂等
- `mergeChunks` 中集成 Redisson `lock:merge:{uploadId}`
- 合并成功后写 Redis `completedKey` 防止重复合并
- **验证**：并发发两个 merge 请求，第二个返回"正在合并中"

#### 任务 2.3：分片 MD5 校验
- `uploadChunk` 中计算接收流的 MD5，与前端传来的 `chunkMd5` 比对
- 不匹配返回 409，删除已落盘的 MinIO 对象
- **验证**：传一个故意损坏的分片，确认 409 响应

### 阶段三：清理与韧性

#### 任务 3.1：过期分片定时清理
- 新建 `task/ChunkCleanupScheduler.java`
- `@Scheduled(cron = "0 0 3 * * ?")` 每天凌晨 3 点执行
- 扫描 Redis `upload:meta:*` ，删除超过 48 小时的 UPLOADING 状态记录
- 同步删除 MinIO 中对应的 `chunks/{uploadId}/` 对象
- **验证**：手动构造过期数据，触发定时任务，确认 Redis + MinIO 均已清理

#### 任务 3.2：文件去重（分层：init 轻量提示 + 合并后精确秒传）
- **init 阶段（轻量启发式）**：后端查询 DB 中 `(userId, fileName, fileSize)` 是否存在 COMPLETED 记录
  - 存在 → 返回 `HINT_DUPLICATE` 状态 + 已有 mediaId，提示用户"资料库中可能存在相同文件，是否跳过？"
  - 用户坚持上传 → 正常走分片流程（不同文件可能同名同大小）
- **merge 阶段（精确 MD5）**：后端调用 `MinioUtils.composeObjects` 合并后，流式计算全文件 MD5
  - 写入 `media_files.file_md5` 字段
  - 检查 `(userId, fileMd5)` 是否已有 COMPLETED 记录 → 如果是重复文件，保留新记录但标记为秒传来源
- **验证**：上传文件 A → 再次上传内容相同的文件 B（不同名）→ 合并后 DB 中两条记录 fileMd5 相同

### 阶段四：前端分片上传逻辑

#### 任务 4.1：创建 useChunkedUpload 组合式函数
- 新建 `client/src/composables/useChunkedUpload.js`
- 实现：
  - **分片级 MD5**：读取每个 5MB 分片时同步计算该分片的 MD5（`File.slice()` + 逐块读入 `ArrayBuffer` + 增量哈希，每片 ~50ms，不阻塞 UI）。不需要全文件 MD5
  - `initUpload(file, userId)` — 发送 fileName、fileSize、totalChunks 到 `/media/api/chunk/init`
  - `uploadAllChunks(uploadId, file, totalChunks, completedSet)` — 过滤已传分片 → 每个分片携带 chunkMd5 → 并发 3 个上传
  - `mergeChunks(uploadId, userId)` — 所有分片确认后调用合并接口
  - 进度追踪（百分比、已上传大小、速度、预计剩余时间）
  - 每个分片失败 3 次重试（指数退避 1s/2s/4s）
  - localStorage 持久化 uploadId + fileName + fileSize + totalChunks（页面刷新可恢复）
- **验证**：用 20MB 文件测试，观察分片上传、进度更新、合并完成

#### 任务 4.2：小文件优化
- 文件 < 5MB（即 totalChunks = 1）时，走现有的 `/media/upload` 整文件上传
- 文件 ≥ 5MB 时，走新的分片上传流程
- **验证**：3MB 文件走旧接口，20MB 文件走新流程

### 阶段五：前端 UI 改造

#### 任务 5.1：App.vue 集成分片上传
- 修改 `uploadFile()` 方法，接入 `useChunkedUpload`
- 保留现有 URL 上传、列表、删除等逻辑不变
- **验证**：上传视频 → 列表中显示 → 可正常删除/下载/转写/AI 分析

#### 任务 5.2：进度条 UI
- 在上传区域（`.magnet-content.busy`）替换通用提示文字
- 显示：进度百分比 + 已上传/总大小 + 上传速度 + 取消按钮
- **验证**：大文件上传时进度条平滑增长，点击取消后停止

#### 任务 5.3：断点续传恢复提示
- `onMounted` 检查 localStorage 是否有未完成的 uploadId
- 调用 check 接口获取已传分片 → 提示用户"是否继续上次上传？"
- 用户确认 → 从断点继续；用户拒绝 → 调用 cancel 清理
- **验证**：上传到一半关闭标签页 → 重新打开 → 看到恢复提示 → 继续上传

### 阶段六：边界情况与完善

#### 任务 6.1：beforeunload 提醒
- 上传进行中时，监听 `beforeunload` 事件
- 弹出浏览器原生确认框："上传正在进行中，离开后需重新恢复"
- **验证**：上传中点击关闭标签页，浏览器弹出确认框

#### 任务 6.2：端到端集成测试
- 完整链路：上传 100MB+ 视频 → 分片上传 → 合并 → 列表中显示 → 转写 → AI 分析
- 断点续传：上传到 30% 关闭标签页 → 重新打开 → 恢复 → 完成
- 秒传验证：上传文件 A 完成 → 再次上传相同文件 A → 后端合并后检测到 fileMd5 重复 → DB 中两条记录 fileMd5 一致
- **验证**：以上三个场景全部通过

---

## 四、验证方式

1. **后端接口验证**：用 curl/Postman 逐个调用 5 个接口，确认全链路
2. **MinIO 验证**：登录 MinIO 控制台 `http://127.0.0.1:9001`，查看 `chunks/` 分片和最终合并文件
3. **Redis 验证**：用 `redis-cli` 查看 `upload:*` 前缀的 key 生命周期
4. **前端验证**：浏览器 DevTools Network 标签观察分片请求、Concurrency 控制、重试行为
5. **端到端**：启动完整环境（`/start-dev`）→ 上传视频 → 列表显示 → 转写和 AI 分析正常工作
