package com.example.server.service;

import com.example.server.mapper.MediaFileMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MediaService {

    // 注入数据库操作接口 (MyBatis-Plus 自动代理)
    private final MediaFileMapper mediaFileMapper;
    private final StringRedisTemplate redisTemplate;

    private static final String UPLOAD_DIR = "D:/Project/MediaApp/uploads/";
    private static final String CHUNK_UPLOAD_KEY_PREFIX = "upload:chunked:";

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
}
