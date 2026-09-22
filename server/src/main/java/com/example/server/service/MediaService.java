package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.AnalysisTaskKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MediaService {

    // 注入数据库操作接口 (MyBatis-Plus 自动代理)
    private final MediaFileMapper mediaFileMapper;
    private final StringRedisTemplate redisTemplate;

    private static final String UPLOAD_DIR = "D:/Project/MediaApp/uploads/";
    private static final String CHUNK_UPLOAD_KEY_PREFIX = "upload:chunked:";
    private static final String MD5_CACHE_KEY_PREFIX = "media:md5:";
    private static final Duration MD5_CACHE_TTL = Duration.ofDays(7);

    public MediaService(MediaFileMapper mediaFileMapper,
                        StringRedisTemplate redisTemplate) {
        this.mediaFileMapper = mediaFileMapper;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    private void init() {
        File dir = new File(UPLOAD_DIR);
        if (!dir.exists()) dir.mkdirs();
    }

    public String initChunkedUpload() {
        String uploadId = UUID.randomUUID().toString();
        String redisKey = CHUNK_UPLOAD_KEY_PREFIX + uploadId;
        redisTemplate.opsForValue().set(redisKey, "INIT", 1, TimeUnit.DAYS);
        return uploadId;
    }

    /**
     * 计算本地文件的内容指纹（MD5）。URL 上传（yt-dlp 下载到本地）场景使用。
     */
    public String calculateMd5(File file) throws IOException {
        try (InputStream in = java.nio.file.Files.newInputStream(file.toPath())) {
            return calculateMd5(in);
        }
    }

    /**
     * 从输入流计算 MD5（32 位小写十六进制）。
     */
    public String calculateMd5(InputStream inputStream) throws IOException {
        MessageDigest digest = md5Digest();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 统一内容指纹获取：Redis 缓存 → DB fileMd5 → 标准化返回。
     * <p>fileMd5 即内容指纹 contentHash（复用现有字段，不新增 DB 列）。</p>
     */
    public String contentHash(Long mediaId) {
        String cacheKey = MD5_CACHE_KEY_PREFIX + mediaId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        String contentHash = AnalysisTaskKeys.normalizeContentHash(mediaId, mediaFile == null ? null : mediaFile.getFileMd5());
        redisTemplate.opsForValue().set(cacheKey, contentHash, MD5_CACHE_TTL);
        return contentHash;
    }

    /**
     * 获取 MD5 摘要器（供直传链路一次 IO 边上传边算指纹复用）。
     */
    public MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 算法不可用", e);
        }
    }
}
