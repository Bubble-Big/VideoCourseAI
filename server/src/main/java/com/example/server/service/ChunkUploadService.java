package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.dto.ChunkUploadDTO;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 分片上传核心业务逻辑
 * <p>
 * 关键 Redis Key：
 * <pre>
 *   upload:meta:{uploadId}         — Hash  元数据
 *   upload:chunks:{uploadId}       — Set   已完成分片序号
 *   upload:chunk:md5:{uploadId}    — Hash  分片序号→MD5
 *   lock:merge:{uploadId}          — RLock 合并分布式锁
 * </pre>
 */
@Service
public class ChunkUploadService {

    private static final String META_KEY_PREFIX = "upload:meta:";
    private static final String CHUNKS_KEY_PREFIX = "upload:chunks:";
    private static final String LOCK_MERGE_PREFIX = "lock:merge:";
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
    public Map<String, Object> initUpload(ChunkUploadDTO.InitRequest req) {
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
        if (req.getUserId() != null) {
            QueryWrapper<MediaFile> dupQuery = new QueryWrapper<>();
            dupQuery.eq("user_id", req.getUserId())
                    .eq("filename", req.getFileName())
                    .eq("file_size", req.getFileSize())
                    .eq("status", "COMPLETED");
            MediaFile dup = mediaFileMapper.selectOne(dupQuery);
            if (dup != null) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("uploadId", null);
                resp.put("status", "HINT_DUPLICATE");
                resp.put("existingMediaId", dup.getId());
                resp.put("completedChunks", List.of());
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
        meta.put("createdAt", String.valueOf(System.currentTimeMillis()));

        redis.opsForHash().putAll(metaKey, meta);
        redis.expire(metaKey, META_TTL_HOURS, TimeUnit.HOURS);

        Map<String, Object> resp = new HashMap<>();
        resp.put("uploadId", uploadId);
        resp.put("status", "NEW");
        resp.put("completedChunks", List.of());
        return resp;
    }

    // ==================== 2. 查询上传状态 ====================

    /**
     * 查询上传任务状态，返回已完成分片集合供前端断点续传
     */
    public Map<String, Object> checkStatus(String uploadId) {
        String metaKey = META_KEY_PREFIX + uploadId;
        Map<Object, Object> meta = redis.opsForHash().entries(metaKey);

        Map<String, Object> resp = new HashMap<>();
        resp.put("uploadId", uploadId);

        if (meta.isEmpty()) {
            resp.put("status", "NOT_FOUND");
            return resp;
        }

        String status = (String) meta.get("status");
        resp.put("status", status);
        resp.put("fileName", meta.get("fileName"));
        resp.put("fileSize", parseLong(meta.get("fileSize")));
        resp.put("totalChunks", parseInt(meta.get("totalChunks")));

        if ("COMPLETED".equals(status)) {
            resp.put("mediaId", parseLong(meta.get("mediaId")));
        }

        // 获取已完成分片集合
        String chunksKey = CHUNKS_KEY_PREFIX + uploadId;
        Set<String> chunkStrs = redis.opsForSet().members(chunksKey);
        if (chunkStrs != null && !chunkStrs.isEmpty()) {
            resp.put("completedChunks", chunkStrs.stream().map(Integer::parseInt).collect(Collectors.toList()));
        } else {
            resp.put("completedChunks", List.of());
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
    public Map<String, Object> mergeChunks(String uploadId, Long userId) throws Exception {
        String lockKey = LOCK_MERGE_PREFIX + uploadId;
        RLock lock = redissonClient.getLock(lockKey);

        // 1. 抢分布式锁
        if (!lock.tryLock(0, 120, TimeUnit.SECONDS)) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "MERGING");
            return resp; // 已有其他线程在合并
        }

        try {
            // 2. 幂等检查：Redis 中可能已标记完成
            String metaKey = META_KEY_PREFIX + uploadId;
            Map<Object, Object> meta = redis.opsForHash().entries(metaKey);
            if ("COMPLETED".equals(meta.get("status"))) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("mediaId", parseLong(meta.get("mediaId")));
                resp.put("filePath", meta.get("filePath"));
                resp.put("status", "COMPLETED");
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

            // 4. 生成最终文件名并执行 MinIO composeObject
            String fileName = (String) meta.get("fileName");
            String suffix = "";
            if (fileName != null && fileName.contains(".")) {
                suffix = fileName.substring(fileName.lastIndexOf("."));
            }
            String targetObjectName = UUID.randomUUID().toString() + suffix;

            minioUtils.composeObjects(uploadId, totalChunks, targetObjectName);
            String fileUrl = minioUtils.getEndpoint() + "/" + minioUtils.getBucketName() + "/" + targetObjectName;

            // 4.5 计算全文件 MD5（从 MinIO 流式读取，不占用服务器磁盘）
            String fileMd5 = computeFileMd5(targetObjectName);

            // 5. 写入 MySQL
            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename(fileName);
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

            Map<String, Object> resp = new HashMap<>();
            resp.put("mediaId", mediaFile.getId());
            resp.put("filePath", fileUrl);
            resp.put("status", "COMPLETED");
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
     * 流式计算 MinIO 中已合并文件的 MD5
     */
    private String computeFileMd5(String objectName) {
        try (InputStream is = minioUtils.getObjectStream(objectName)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
            // 完成哈希计算
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            System.err.println("全文件 MD5 计算失败，使用空值: " + e.getMessage());
            return null;
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
