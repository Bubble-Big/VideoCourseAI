# 视频分片上传 + 断点续传重构计划（最终版）

> 本文档记录重构的完整设计、实际实施的修改以及与最初构想的差异。
> 最后更新：2026-08-13

---
## 〇、最初构想
现行视频上传流程未实现所构想的分片上传+断点重续的设计，在大文件传输与弱网环境下会影响体验。以下是视频上传流程构想：
1. 视频切片。前端读取视频文件后，按照 5MB 的固定大小进行切片。后续的每次分片上传请求都会携带四个关键参数：
  ① 当前上传视频的任务会话标号uploadId 
  ② 分片序号chunkIndex 
  ③ 当前视频总分片数totalChunks 
  ④ 分片视频文件流
2. 上传前校验。在正式上传前，前端先向后端发一个check 请求，携带该视频文件的uploadId、fileName以及fileSize；后端通过这三个数据去 Redis 查询当前文件的状态，分三种情况处理：
  ① 进行中（Redis中存在该视频任务会话标号的分片记录）→ 后端从 Redis 的 Set 中取出已成功落盘的分片序号集合，以数组形式返回给前端。前端经过比对就知道该跳过哪些、从哪一片继续传，从而实现断点续传。
  ② 已完成（Redis中查不到相同视频任务会话标号，但存在同文件名同大小的视频已传输完成记录）→ 提示用户"同名视频文件{file_name}资料库中已存在，可能为重复文件，是否继续上传？" 若用户同意则直接终止任务，前端跳过整个上传流程；若用户坚持上传，则当做新任务正常上传。
  ③ 无记录（三个数据无任何相同记录） → 当作新任务，从第一个分片开始上传
3. 并发上传分片。前端过滤掉已传分片后，携带四个关键参数并发发起请求，并发数输出数量需进行控制，避免浏览器连接数耗尽。后端处理时，严格遵循 "先落盘，后记账" 的策略，存储分片前，先对四个参数进行判空、文件大小限制、uploadId与totalChunks比对等合法性检验，通过之后会按照设定结构存储到 MinIO 对象中对应的位置。等 MinIO 保存成功后，后端才会去 Redis 的 Set 中记录该分片的序号。若校验不通过，不接受并抛出错误等重发
4. 分片合并。当前端收到所有分片的成功接收ack后判断某所有分片集齐，发起合并请求，后端接收到合并请求先抢分布式锁，失败表示已有其他发起合并的线程抢锁成功，返回正在合并中；若成功，则进行幂等检查，查redis中是否有视频已合并完成的completedKey，若有直接返回合并成功的视频文件；若没有，则正常进行视频合并并计算视频md5；合并完成后上传minio，并将视频信息写入mysql，将合并完成completedKey写入redis；最后清理分片数据并释放分布式锁

---

## 一、对用户构想的评审

### 保留的设计

| 设计点 | 评价 |
|--------|------|
| **5MB 固定切片** | 合理。平衡请求次数与重传代价，1000 片可覆盖 5GB 视频 |
| **uploadId + chunkIndex + totalChunks 协议** | 标准做法，与 S3 Multipart Upload、tus.io 一致 |
| **check 接口做状态查询，返回已传分片集合** | 核心设计正确，前端过滤后实现真正的断点续传 |
| **并发上传分片** | 合理，控制并发数 3 个，避免浏览器连接数耗尽 |
| **合并时分布式锁 + 幂等检查** | 正确的防重复合并方案 |
| **前端集齐后发起合并请求** | 职责清晰 |

### 调整的设计

1. **uploadId 由后端生成**：InitRequest 发送 fileName/fileSize/totalChunks，后端生成 UUID 返回。防止前端伪造 uploadId。

2. **去重策略分层**：
   - **轻量层（init 时）**：后端用 `(userId, fileName, fileSize)` 做启发式匹配，命中则提示用户
   - **精确层（合并后）**：后端合并分片后从 MinIO 流式计算全文件 MD5，存入 `media_files.file_md5`

3. **"先落盘后记账"细化**：校验 → MinIO 落盘 → Redis SADD。合并时只看 Redis Set 中的已完成序号。

4. **MinIO `composeObject` 服务端合并**：零下载带宽，最多 1000 个源分片。

### 实施过程中明确放弃的设计

| 放弃项 | 原因 |
|--------|------|
| **前端分片级 MD5 校验** | 引入 spark-md5 依赖导致 npm 安装权限问题；改为信任 HTTP 传输完整性 |
| **Java 定时任务清理过期分片** | 过度设计。Redis 48h TTL 自动过期 + MinIO 生命周期规则 `chunks/` 前缀 2 天自动清理，无需代码 |
| **MinIO 生命周期 SDK 调用** | SDK 版本 API 差异导致编译错误。改为 MinIO 控制台或 `mc` CLI 手动配置，一次配完永久生效 |

### 实施过程中新增的设计

| 新增项 | 说明 |
|--------|------|
| **统一响应体 `Result<T>`** | `{code, message, data}` 结构，code=0 表成功。Java record 实现 |
| **统一错误码 `ErrorCode`** | 枚举集中管理 (400/401/403/404/409/422/500) |
| **全局异常处理 `ApiExceptionHandler`** | `@RestControllerAdvice` 统一转换异常为 Result 响应，Controller 无需 try-catch |
| **业务异常 `BusinessException`** | 携带 ErrorCode 语义，与系统异常分流 |
| **Redisson 3.23.5 → 3.52.0** | 原版本与 Spring Boot 3.5.9 不兼容，导致 StackOverflowError |
| **`spring-boot-starter-validation`** | 补充参数校验基础设施 |
| **`@RequestBody` 使用 `Map<String,Object>`** | fastjson2 对静态内部类反序列化存在兼容性问题 |

---

## 二、架构设计要点（最终版）

### 2.1 新增接口

所有接口在 `/media/api/chunk` 路径下，统一返回 `Result<T>` 格式：

| 方法 | 路径 | 请求体 | 响应 data 类型 |
|------|------|--------|---------------|
| `POST` | `/media/api/chunk/init` | `{fileName, fileSize, totalChunks, userId, force}` | `InitResponse` |
| `POST` | `/media/api/chunk/check` | `{uploadId}` | `CheckResponse` |
| `POST` | `/media/api/chunk/upload` | Multipart: uploadId, chunkIndex, file | `{chunkIndex, status}` |
| `POST` | `/media/api/chunk/merge` | `{uploadId, userId}` | `MergeResponse` |
| `DELETE` | `/media/api/chunk/cancel` | `{uploadId}` | `{status: "CANCELLED"}` |

> 注：`@RequestBody` 参数在 Controller 层使用 `Map<String, Object>` 接收，手动提取字段后构造 DTO。因 fastjson2 对静态内部类反序列化存在兼容性问题。

### 2.2 Redis Key 设计

| Key 模式 | 类型 | 内容 | TTL |
|----------|------|------|-----|
| `upload:meta:{uploadId}` | Hash | fileName, fileSize, totalChunks, userId, status, forceUpload, createdAt, mediaId(合并后) | 48h |
| `upload:chunks:{uploadId}` | Set | 已成功上传的分片序号（如 "0","1","5"） | 48h |
| `lock:merge:{uploadId}` | RLock | Redisson 分布式锁，leaseTime=120s | — |

### 2.3 MinIO 存储结构

```
media/                            (现有 bucket)
  chunks/                         (分片临时存储 — 生命周期 2 天自动过期)
    {uploadId}/
      0, 1, 2, ...               (分片对象，序号为名)
  {uuid}.mp4                      (最终合并文件，保持不变)
```

**过期清理**：
- Redis：48h TTL 自动过期
- MinIO：控制台或 `mc ilm rule add --expire-days 2 --prefix "chunks/" local/media` 配置生命周期规则

### 2.4 数据库变更

`media_files` 表新增字段（已执行迁移）：

```sql
ALTER TABLE media_files
    ADD COLUMN file_size BIGINT DEFAULT NULL COMMENT '文件大小(字节)',
    ADD COLUMN file_md5 VARCHAR(32) DEFAULT NULL COMMENT '全文件MD5哈希(后端合并后计算)';

CREATE INDEX idx_media_files_user_md5 ON media_files(user_id, file_md5);
```

### 2.5 关键决策

| 决策 | 选择 | 原因 |
|------|------|------|
| 合并策略 | MinIO `composeObject` | 服务端合并，零下载带宽，最多 1000 源对象 |
| 前端并发数 | 3 | 浏览器同域最多 6 连接，留 3 给其他请求 |
| 过期时间 | 48 小时 | 跨周末可恢复，过期自动清理 |
| 过期清理方式 | Redis TTL + MinIO 生命周期 | 零代码维护，基础设施层自动处理 |
| 分片大小上限 | 5MB × 10000 = 50GB | 合法上限，超出拒绝 |
| 全文件 MD5 | 后端合并后从 MinIO 流式计算 | GB 级文件前端计算太慢（数分钟） |
| init 去重 | `(userId, fileName, fileSize)` 轻量提示 | 不依赖前端全文件哈希 |
| 坚持上传 (force) | init 加 `force` 字段，跳过轻量去重 | 用户确认后正常分片上传 |
| force 后去重 | 合并后 MD5 比对同名文件 | 相同→删新+更新旧时间；不同→文件名加 `(1)`/`(2)` 后缀 |
| 断点续传匹配 | 文件指纹 `fileName + fileSize + lastModified` | 页面刷新后重新选择同一文件可自动识别 |
| 列表排序 | `ORDER BY upload_time DESC` | 去重替换时更新旧记录时间即可自然置顶 |
| 统一响应格式 | `Result<T>` (Java record) | code/message/data 三段式，全 API 一致 |
| 异常处理 | `@RestControllerAdvice` 全局处理 | Controller 层零 try-catch |
| Redisson 版本 | 3.52.0 | 兼容 Spring Boot 3.5.x |

---

## 三、实施完成清单

### 阶段一：后端基础设施 ✅

| 任务 | 状态 | 说明 |
|------|------|------|
| 1.1 数据库表扩展 | ✅ | `file_size` (BIGINT) + `file_md5` (VARCHAR(32)) + 联合索引。SQL 脚本在 `db/` 目录 |
| 1.2 MinioUtils 扩展 | ✅ | 新增 `uploadChunkObject`、`composeObjects`、`deleteChunkObjects`、`listChunkObjects`、`getObjectStream`、`getEndpoint`/`getBucketName` |
| 1.3 ChunkUploadService | ✅ | `initUpload`、`checkStatus`、`uploadChunk`、`mergeChunks`、`cancelUpload`。全文件 MD5 流式计算 |

### 阶段二：后端接口与集成 ✅

| 任务 | 状态 | 说明 |
|------|------|------|
| 2.1 ChunkController | ✅ | 5 个端点，返回 `Result<T>` 统一格式，`@RequestBody` 使用 `Map<String,Object>` |
| 2.2 分布式锁 + 幂等 | ✅ | Redisson `lock:merge:{uploadId}` + Redis COMPLETED 状态幂等检查 |
| 2.3 分片 MD5 校验 | ❌ 放弃 | 见上文"放弃的设计" |

### 阶段三：清理与韧性 ✅

| 任务 | 状态 | 说明 |
|------|------|------|
| 3.1 过期分片清理 | ✅ | Redis 48h TTL + MinIO 生命周期规则（mc CLI 已配置） |
| 3.2 文件去重 | ✅ | init 轻量提示 (fileName+fileSize) + merge 后精确 MD5 |

### 阶段四：前端分片上传逻辑 ✅

| 任务 | 状态 | 说明 |
|------|------|------|
| 4.1 useChunkedUpload | ✅ | 分片切片、并发控制(3)、指数退避重试(3次)、进度追踪(速度/ETA)、localStorage 持久化 |
| 4.2 小文件优化 | ✅ | < 5MB 走 `/media/upload` 整文件上传，≥ 5MB 走分片流程 |

### 阶段五：前端 UI 改造 ✅

| 任务 | 状态 | 说明 |
|------|------|------|
| 5.1 App.vue 集成 | ✅ | `uploadFile()` 接入 `useChunkedUpload`，watch 状态变化响应完成/错误/去重 |
| 5.2 进度条 UI | ✅ | 百分比 + 已上传/总大小 + 速度 + 取消按钮 |
| 5.3 断点续传恢复 | ✅ | 双场景：① 页面未刷新 → 内存 File + 续传横幅一键继续；② 页面刷新 → 文件指纹匹配 + 重新选择自动续传 |

### 阶段六：边界情况与完善 ✅

| 任务 | 状态 | 说明 |
|------|------|------|
| 6.1 beforeunload 提醒 | ✅ | 上传中关闭标签页弹出确认框 |
| 6.2 端到端测试 | ✅ | 全链路：分片上传→合并→列表显示→转写→AI 分析 |

### 补充任务（实施过程中新增）

| 任务 | 状态 | 说明 |
|------|------|------|
| 7.1 Redisson 版本升级 | ✅ | `pom.xml`: 3.23.5 → 3.52.0，解决 StackOverflowError |
| 7.2 统一响应体 Result<T> | ✅ | `common/Result.java` (Java record) |
| 7.3 统一错误码 ErrorCode | ✅ | `common/ErrorCode.java` (枚举) |
| 7.4 全局异常处理 | ✅ | `controller/ApiExceptionHandler.java` (@RestControllerAdvice) |
| 7.5 业务异常类 | ✅ | `exception/BusinessException.java` |
| 7.6 数据库建表脚本 | ✅ | `db/schema.sql` (users + media_files 完整建表) |
| 7.7 文档修订 | ✅ | README.md、ARCHITECTURE.md、CLAUDE.md |
| 7.8 续传横幅 UI | ✅ | 内嵌横幅（绿色）+ 一键继续/重新开始按钮 |
| 7.9 去重提示横幅 | ✅ | 内嵌横幅（红色警告），替代 confirm 弹窗 |
| 7.10 坚持上传 force 流程 | ✅ | init 加 force 字段，合并后 MD5 比对（相同替换/不同加后缀） |
| 7.11 文件指纹匹配 | ✅ | `fileName + fileSize + lastModified` 匹配 localStorage 会话 |
| 7.12 列表排序优化 | ✅ | `ORDER BY upload_time DESC` + 前端去掉 reverse |
| 7.13 工作台横排布局 | ✅ | 3 列卡片 → 单列横排列表，文件名左、按钮右 |

---

## 四、文件变更清单

### 新增文件（13 个）

```
server/src/main/java/com/example/server/
├── common/Result.java
├── common/ErrorCode.java
├── controller/ChunkController.java
├── controller/ApiExceptionHandler.java
├── service/ChunkUploadService.java
├── dto/ChunkUploadDTO.java
├── exception/BusinessException.java
└── resources/db/
    ├── schema.sql
    └── V1__add_file_size_and_md5.sql

client/src/composables/useChunkedUpload.js

CHUNKED_UPLOAD_PLAN.md
```

### 修改文件（9 个）

```
server/pom.xml                                    # Redisson 3.52.0 + validation
server/src/main/java/.../config/MinioConfig.java   # 生命周期注释
server/src/main/java/.../entity/MediaFile.java     # +fileSize, fileMd5
server/src/main/java/.../utils/MinioUtils.java     # +5 方法 + getter
client/package.json                                # (spark-md5 已移除)
client/src/App.vue                                 # 分片上传集成 + 进度条 + beforeunload
README.md                                         # 技术栈/功能描述更新
ARCHITECTURE.md                                   # 全面更新
CLAUDE.md                                         # 全面更新
```

---

## 五、验证方式

1. **后端接口验证**：`curl` 逐个调用 5 个接口，init → upload × N → check → merge
2. **MinIO 验证**：登录 `http://127.0.0.1:9001`，查看 `chunks/` 分片和最终合并文件
3. **MinIO 生命周期**：`docker exec minio mc ilm rule ls local/media` 确认规则已生效
4. **Redis 验证**：`redis-cli` 查看 `upload:*` 前缀的 key 生命周期
5. **前端验证**：DevTools Network 标签观察分片请求、并发控制、重试行为
6. **端到端**：`/start-dev` → 上传视频 → 列表显示 → 转写和 AI 分析
