package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.dto.ChunkUploadDTO;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 分片上传核心业务逻辑
 * <p>
 * 关键 Redis Key：
 * <pre>
 *   upload:meta:{uploadId}         — Hash  元数据 (fileName, fileSize, totalChunks, userId, status)
 *   upload:chunks:{uploadId}       — Set   已完成分片序号
 *   lock:merge:{uploadId}          — RLock 合并分布式锁
 * </pre>
 */
@Service
public class ChunkUploadService {

    private static final String META_KEY_PREFIX = "upload:meta:";
    private static final String CHUNKS_KEY_PREFIX = "upload:chunks:";
    private static final String LOCK_MERGE_PREFIX = "lock:merge:";
    private static final String CHUNK_OBJECT_PREFIX = "chunks/";
    private static final long META_TTL_HOURS = 48;

    private final StringRedisTemplate redis;
    private final RedissonClient redissonClient;
    private final MinioUtils minioUtils;
    private final MediaFileMapper mediaFileMapper;

    public ChunkUploadService(StringRedisTemplate redis,
                              RedissonClient redissonClient,
                              MinioUtils minioUtils,
                              MediaFileMapper mediaFileMapper) {
        this.redis = redis;
        this.redissonClient = redissonClient;
        this.minioUtils = minioUtils;
        this.mediaFileMapper = mediaFileMapper;
    }

    // ==================== 1. 初始化上传 ====================

    /**
     * 初始化分片上传任务
     * <p>
     * 三种返回状态：
     * <ul>
     *   <li>NEW — 全新上传，后端生成 uploadId</li>
     *   <li>RESUME — 断点续传，返回已完成分片集合</li>
     *   <li>HINT_DUPLICATE — 可能存在同名同大小文件，提示用户</li>
     * </ul>
     */
    public ChunkUploadDTO.InitResponse initUpload(ChunkUploadDTO.InitRequest req) {
        // 1. 参数合法性校验
        if (req.getFileName() == null || req.getFileName().isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (req.getFileSize() == null || req.getFileSize() <= 0) {
            throw new IllegalArgumentException("文件大小不合法");
        }
        if (req.getTotalChunks() == null || req.getTotalChunks() < 1 || req.getTotalChunks() > 10000) {
            throw new IllegalArgumentException("分片数必须在 1~10000 之间，当前: " + req.getTotalChunks());
        }

        // 2. 轻量去重检测：查询 DB 中 (userId, fileName, fileSize) 是否存在已完成记录
        // 若用户坚持上传 (force=true)，跳过此步骤，到合并后再做精确 MD5 判断
        if (!req.isForce() && req.getUserId() != null) {
            QueryWrapper<MediaFile> dupQuery = new QueryWrapper<>();
            dupQuery.eq("user_id", req.getUserId())
                    .eq("filename", req.getFileName())
                    .eq("file_size", req.getFileSize())
                    .eq("status", "COMPLETED");
            MediaFile dup = mediaFileMapper.selectOne(dupQuery);
            if (dup != null) {
                ChunkUploadDTO.InitResponse resp = new ChunkUploadDTO.InitResponse(null, "HINT_DUPLICATE");
                resp.setExistingMediaId(dup.getId());
                resp.setCompletedChunks(Collections.emptySet());
                return resp;
            }
        }

        // 3. 生成 uploadId，写入 Redis meta Hash
        String uploadId = UUID.randomUUID().toString();
        String metaKey = META_KEY_PREFIX + uploadId;

        Map<String, String> meta = new HashMap<>();
        meta.put("fileName", req.getFileName());
        meta.put("fileSize", String.valueOf(req.getFileSize()));
        meta.put("totalChunks", String.valueOf(req.getTotalChunks()));
        meta.put("userId", req.getUserId() == null ? "" : String.valueOf(req.getUserId()));
        meta.put("status", "UPLOADING");
        meta.put("forceUpload", req.isForce() ? "1" : "0");
        meta.put("createdAt", String.valueOf(System.currentTimeMillis()));

        redis.opsForHash().putAll(metaKey, meta);
        redis.expire(metaKey, META_TTL_HOURS, TimeUnit.HOURS);

        ChunkUploadDTO.InitResponse resp = new ChunkUploadDTO.InitResponse(uploadId, "NEW");
        resp.setCompletedChunks(Collections.emptySet());
        return resp;
    }

    // ==================== 2. 查询上传状态 ====================

    /**
     * 查询上传任务状态，返回已完成分片集合供前端断点续传
     */
    public ChunkUploadDTO.CheckResponse checkStatus(String uploadId) {
        String metaKey = META_KEY_PREFIX + uploadId;
        Map<Object, Object> meta = redis.opsForHash().entries(metaKey);

        ChunkUploadDTO.CheckResponse resp = new ChunkUploadDTO.CheckResponse();
        resp.setUploadId(uploadId);

        if (meta.isEmpty()) {
            resp.setStatus("NOT_FOUND");
            return resp;
        }

        String status = (String) meta.get("status");
        resp.setStatus(status);
        resp.setFileName((String) meta.get("fileName"));
        resp.setFileSize(parseLong(meta.get("fileSize")));
        resp.setTotalChunks(parseInt(meta.get("totalChunks")));

        if ("COMPLETED".equals(status)) {
            resp.setMediaId(parseLong(meta.get("mediaId")));
        }

        // 获取已完成分片集合
        String chunksKey = CHUNKS_KEY_PREFIX + uploadId;
        Set<String> chunkStrs = redis.opsForSet().members(chunksKey);
        if (chunkStrs != null && !chunkStrs.isEmpty()) {
            resp.setCompletedChunks(chunkStrs.stream().map(Integer::parseInt).collect(Collectors.toSet()));
        } else {
            resp.setCompletedChunks(Collections.emptySet());
        }

        return resp;
    }

    // ==================== 3. 上传单个分片 ====================

    /**
     * 上传一个分片
     * <p>
     * 流程：① 校验 uploadId/chunkIndex → ② 幂等检查 → ③ MinIO 落盘 → ④ Redis COMPLETED
     *
     * @param uploadId   上传任务ID
     * @param chunkIndex 分片序号
     * @param file       分片文件 (MultipartFile)
     */
    public void uploadChunk(String uploadId, int chunkIndex, MultipartFile file) throws Exception {
        // 1. 校验 uploadId 存在且状态为 UPLOADING
        String metaKey = META_KEY_PREFIX + uploadId;
        String status = (String) redis.opsForHash().get(metaKey, "status");
        if (status == null) {
            throw new IllegalArgumentException("uploadId 不存在: " + uploadId);
        }
        if (!"UPLOADING".equals(status)) {
            throw new IllegalStateException("上传任务状态异常: " + status);
        }

        // 2. 校验 chunkIndex 范围
        String totalChunksStr = (String) redis.opsForHash().get(metaKey, "totalChunks");
        int totalChunks = Integer.parseInt(totalChunksStr);
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new IllegalArgumentException("分片序号越界: " + chunkIndex + " (总数: " + totalChunks + ")");
        }

        // 3. 幂等：若已存在则直接返回
        String chunksKey = CHUNKS_KEY_PREFIX + uploadId;
        if (Boolean.TRUE.equals(redis.opsForSet().isMember(chunksKey, String.valueOf(chunkIndex)))) {
            return; // 分片已上传，幂等返回
        }

        // 4. 读取分片数据
        byte[] chunkData = file.getBytes();

        // 5. 上传到 MinIO
        java.io.ByteArrayInputStream byteStream = new java.io.ByteArrayInputStream(chunkData);
        minioUtils.uploadChunkObject(uploadId, chunkIndex, byteStream, chunkData.length);

        // 6. 写入 Redis 已完成集合
        redis.opsForSet().add(chunksKey, String.valueOf(chunkIndex));
        redis.expire(chunksKey, META_TTL_HOURS, TimeUnit.HOURS);
    }

    // ==================== 4. 合并分片 ====================

    /**
     * 合并所有分片为最终视频文件
     * <p>
     * 流程：抢分布式锁 → 幂等检查 → 校验全集 → composeObject → 计算 MD5 → 写 DB → 清理
     */
    public ChunkUploadDTO.MergeResponse mergeChunks(String uploadId, Long userId) throws Exception {
        String lockKey = LOCK_MERGE_PREFIX + uploadId;
        RLock lock = redissonClient.getLock(lockKey);

        // 1. 抢分布式锁（无参 tryLock：leaseTime=-1，走看门狗自动续期，避免合并耗时超过 120s 后锁被提前释放）
        if (!lock.tryLock()) {
            ChunkUploadDTO.MergeResponse resp = new ChunkUploadDTO.MergeResponse();
            resp.setStatus("MERGING");
            return resp; // 已有其他线程在合并
        }

        try {
            // 2. 幂等检查：Redis 中可能已标记完成
            String metaKey = META_KEY_PREFIX + uploadId;
            Map<Object, Object> meta = redis.opsForHash().entries(metaKey);
            if ("COMPLETED".equals(meta.get("status"))) {
                ChunkUploadDTO.MergeResponse resp = new ChunkUploadDTO.MergeResponse();
                resp.setMediaId(parseLong(meta.get("mediaId")));
                resp.setFilePath((String) meta.get("filePath"));
                resp.setStatus("COMPLETED");
                return resp;
            }

            // 3. 校验所有分片是否集齐
            String totalChunksStr = (String) meta.get("totalChunks");
            int totalChunks = Integer.parseInt(totalChunksStr);
            String chunksKey = CHUNKS_KEY_PREFIX + uploadId;
            Long completedCount = redis.opsForSet().size(chunksKey);

            if (completedCount == null || completedCount != totalChunks) {
                // 计算缺失的分片
                Set<String> chunkStrs = redis.opsForSet().members(chunksKey);
                Set<Integer> have = chunkStrs != null
                        ? chunkStrs.stream().map(Integer::parseInt).collect(Collectors.toSet())
                        : Collections.emptySet();
                List<Integer> missing = new ArrayList<>();
                for (int i = 0; i < totalChunks; i++) {
                    if (!have.contains(i)) missing.add(i);
                }
                throw new IllegalStateException(
                        "分片未集齐: 已完成 " + completedCount + "/" + totalChunks + "，缺失: " + missing);
            }

            // 4. 合并分片到本地临时文件，边写边算 MD5（与 DOVideoAI 一致，一次 IO 完成合并与哈希）
            String fileName = (String) meta.get("fileName");
            String suffix = "";
            if (fileName != null && fileName.contains(".")) {
                suffix = fileName.substring(fileName.lastIndexOf("."));
            }

            String fileMd5 = null;
            String fileUrl = null;
            Path mergedFile = Files.createTempFile("videocourse-merged-", suffix);
            MessageDigest digest = md5Digest();
            try {
                try (OutputStream fileOutput = Files.newOutputStream(mergedFile);
                     DigestOutputStream digestOutput = new DigestOutputStream(fileOutput, digest);
                     BufferedOutputStream output = new BufferedOutputStream(digestOutput)) {
                    for (int i = 0; i < totalChunks; i++) {
                        minioUtils.copyObjectTo(chunkObjectName(uploadId, i), output);
                    }
                }
                fileMd5 = HexFormat.of().formatHex(digest.digest());
                fileUrl = minioUtils.uploadLocalFile(mergedFile.toFile(), fileName);
            } finally {
                Files.deleteIfExists(mergedFile);
            }

            // 4.5 坚持上传的去重逻辑：MD5 比对同名文件
            String finalFileName = fileName;
            boolean isForce = "1".equals(meta.get("forceUpload"));
            if (isForce && userId != null) {
                // 查找同名同大小的已完成文件
                QueryWrapper<MediaFile> dupQuery = new QueryWrapper<>();
                dupQuery.eq("user_id", userId)
                        .eq("filename", fileName)
                        .eq("file_size", parseLong(meta.get("fileSize")))
                        .eq("status", "COMPLETED");
                MediaFile existing = mediaFileMapper.selectOne(dupQuery);
                if (existing != null && existing.getFileMd5() != null && existing.getFileMd5().equals(fileMd5)) {
                    // MD5 相同 → 同一文件，删除新文件，更新旧文件时间
                    minioUtils.removeFile(fileUrl);
                    existing.setUploadTime(LocalDateTime.now());
                    mediaFileMapper.update(null, new LambdaUpdateWrapper<MediaFile>()
                        .eq(MediaFile::getId, existing.getId())
                        .set(MediaFile::getUploadTime, LocalDateTime.now()));
                    // 返回已有记录
                    redis.opsForHash().put(metaKey, "status", "COMPLETED");
                    redis.opsForHash().put(metaKey, "mediaId", String.valueOf(existing.getId()));
                    redis.opsForHash().put(metaKey, "filePath", existing.getFilePath());
                    cleanUpChunks(uploadId);
                    if (userId != null) { redis.delete("media:list:user:" + userId); }
                    ChunkUploadDTO.MergeResponse resp = new ChunkUploadDTO.MergeResponse();
                    resp.setMediaId(existing.getId());
                    resp.setFilePath(existing.getFilePath());
                    resp.setStatus("COMPLETED");
                    return resp;
                } else if (existing != null && existing.getFileMd5() != null) {
                    // MD5 不同 → 同名不同文件，添加防重名后缀
                    finalFileName = findAvailableFileName(fileName, userId);
                }
            }

            // 5. 写入 MySQL
            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename(finalFileName);
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setFileSize(parseLong(meta.get("fileSize")));
            mediaFile.setFileMd5(fileMd5);
            mediaFile.setUploadTime(LocalDateTime.now());
            if (userId != null) {
                mediaFile.setUserId(userId);
            }

            mediaFileMapper.insert(mediaFile);

            // 6. 更新 Redis meta 为 COMPLETED（幂等标记）
            redis.opsForHash().put(metaKey, "status", "COMPLETED");
            redis.opsForHash().put(metaKey, "mediaId", String.valueOf(mediaFile.getId()));
            redis.opsForHash().put(metaKey, "filePath", fileUrl);
            redis.expire(metaKey, META_TTL_HOURS, TimeUnit.HOURS);

            // 7. 异步清理分片数据（MinIO chunks + Redis chunk keys）
            cleanUpChunks(uploadId);

            // 8. 清除用户列表缓存
            if (userId != null) {
                redis.delete("media:list:user:" + userId);
            }

            ChunkUploadDTO.MergeResponse resp = new ChunkUploadDTO.MergeResponse();
            resp.setMediaId(mediaFile.getId());
            resp.setFilePath(fileUrl);
            resp.setStatus("COMPLETED");
            return resp;

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // ==================== 5. 取消上传 ====================

    /**
     * 取消上传任务，清理 MinIO 分片 + Redis 所有相关 key
     */
    public void cancelUpload(String uploadId) {
        // 清理 MinIO 分片
        minioUtils.deleteChunkObjects(uploadId);

        // 清理 Redis 所有相关 key
        redis.delete(Arrays.asList(
                META_KEY_PREFIX + uploadId,
                CHUNKS_KEY_PREFIX + uploadId
        ));

        System.out.println("上传已取消: " + uploadId);
    }

    // ==================== 内部工具方法 ====================

    /**
     * 清理分片临时数据（MinIO 分片对象 + Redis chunk keys）
     */
    private void cleanUpChunks(String uploadId) {
        try {
            minioUtils.deleteChunkObjects(uploadId);
            redis.delete(CHUNKS_KEY_PREFIX + uploadId);
        } catch (Exception e) {
            System.err.println("分片清理异常 [" + uploadId + "]: " + e.getMessage());
        }
    }

    /**
     * 分片对象在 MinIO 中的对象名。
     */
    private String chunkObjectName(String uploadId, int chunkIndex) {
        return CHUNK_OBJECT_PREFIX + uploadId + "/" + chunkIndex;
    }

    /**
     * 获取 MD5 摘要器（与 DOVideoAI 一致，缺失时视为服务端故障，抛出异常而非静默降级）。
     */
    private MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }

    /**
     * 查找可用的文件名：如果重名，添加 (1)、(2) 等后缀
     */
    private String findAvailableFileName(String originalName, Long userId) {
        String base = originalName;
        String ext = "";
        int dotIdx = originalName.lastIndexOf('.');
        if (dotIdx > 0) {
            base = originalName.substring(0, dotIdx);
            ext = originalName.substring(dotIdx);
        }

        int counter = 1;
        String candidate = originalName;
        while (true) {
            QueryWrapper<MediaFile> q = new QueryWrapper<>();
            q.eq("user_id", userId).eq("filename", candidate).eq("status", "COMPLETED");
            if (mediaFileMapper.selectOne(q) == null) {
                return candidate;
            }
            candidate = base + "(" + counter + ")" + ext;
            counter++;
        }
    }

    private Long parseLong(Object obj) {
        if (obj == null) return null;
        try {
            return Long.valueOf(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInt(Object obj) {
        if (obj == null) return null;
        try {
            return Integer.valueOf(obj.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
