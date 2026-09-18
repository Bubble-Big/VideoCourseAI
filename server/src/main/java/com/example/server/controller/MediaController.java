package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.dto.MediaFileVO;
import com.example.server.entity.MediaAiAnalysis;
import com.example.server.entity.MediaFile;
import com.example.server.entity.MediaTranscription;
import com.example.server.mapper.MediaAiAnalysisMapper;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.MediaTranscriptionMapper;
import com.example.server.service.AiService;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/media")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class MediaController {

    private final MediaFileMapper mediaFileMapper;
    private final MediaAiAnalysisMapper aiAnalysisMapper;
    private final MediaTranscriptionMapper transcriptionMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MinioUtils minioUtils;
    private final YtDlpUtils ytDlpUtils;
    private final MediaService mediaService;
    private final MediaAiAnalysisMapper mediaAiAnalysisMapper;
    private final MediaTranscriptionMapper mediaTranscriptionMapper;
    private final AiService aiService;

    public MediaController(Optional<MediaFileMapper> mediaFileMapper,
                           MediaAiAnalysisMapper aiAnalysisMapper,
                           MediaTranscriptionMapper transcriptionMapper,
                           StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper,
                           MinioUtils minioUtils,
                           YtDlpUtils ytDlpUtils,
                           MediaService mediaService,
                           AiService aiService) {
        this.mediaFileMapper = mediaFileMapper.orElse(null);
        this.aiAnalysisMapper = aiAnalysisMapper;
        this.transcriptionMapper = transcriptionMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.minioUtils = minioUtils;
        this.ytDlpUtils = ytDlpUtils;
        this.mediaService = mediaService;
        this.mediaAiAnalysisMapper = aiAnalysisMapper;
        this.mediaTranscriptionMapper = transcriptionMapper;
        this.aiService = aiService;
    }

    @PostMapping("/init-upload")
    public ResponseEntity<String> initUpload() {
        String uploadId = mediaService.initChunkedUpload();
        return ResponseEntity.ok(uploadId);
    }

    @PostMapping("/upload-url")
    public org.springframework.http.ResponseEntity<String> uploadUrl(@RequestParam("url") String url,
                                                                     @RequestParam(value = "userId", required = false) Long userId) {
        File tempFile = null;
        try {
            if (url == null || url.isBlank()) {
                return org.springframework.http.ResponseEntity.badRequest().body("Upload failed: url is empty");
            }
            if (mediaFileMapper == null) {
                return org.springframework.http.ResponseEntity.status(500).body("Upload failed: database not ready");
            }
            System.out.println("Received upload url: " + url);

            tempFile = ytDlpUtils.downloadVideo(url);

            String fileMd5 = mediaService.calculateMd5(tempFile);   // 先算内容指纹

            // MD5 去重：同用户已有相同内容的已完成记录 → 复用旧记录，不重复上传/入库
            if (userId != null) {
                QueryWrapper<MediaFile> dupQuery = new QueryWrapper<>();
                dupQuery.eq("user_id", userId)
                        .eq("file_md5", fileMd5)
                        .eq("status", "COMPLETED");
                MediaFile existing = mediaFileMapper.selectOne(dupQuery);
                if (existing != null) {
                    // 检查子表数据完整性：AI 分析和转写是否都已完成
                    boolean hasCompleteAi = mediaAiAnalysisMapper.selectOne(
                        new QueryWrapper<MediaAiAnalysis>()
                            .eq("media_id", existing.getId())
                            .eq("status", "COMPLETED")
                    ) != null;

                    boolean hasCompleteTranscript = mediaTranscriptionMapper.selectOne(
                        new QueryWrapper<MediaTranscription>()
                            .eq("media_id", existing.getId())
                            .eq("status", "COMPLETED")
                    ) != null;

                    // MD5 相同 → 复用旧记录，刷新上传时间并失效缓存，不重复上传 MinIO
                    mediaFileMapper.update(null, new LambdaUpdateWrapper<MediaFile>()
                        .eq(MediaFile::getId, existing.getId())
                        .set(MediaFile::getUploadTime, LocalDateTime.now()));
                    redisTemplate.delete("media:list:user:" + userId);

                    // 如果子表数据不完整，重新触发 AI 分析任务（补全缺失数据）
                    if (!hasCompleteAi || !hasCompleteTranscript) {
                        System.out.println("MD5 去重命中但子表数据不完整，重新触发 AI 分析 mediaId=" + existing.getId());
                        aiService.asyncAnalyze(existing.getId(), false);
                    } else {
                        System.out.println("MD5 去重命中，复用已有完整记录 mediaId=" + existing.getId());
                    }

                    return ResponseEntity.ok("Upload success (deduplicated)");
                }
            }

            String fileUrl = minioUtils.uploadLocalFile(tempFile);

            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename("WEB_" + tempFile.getName());
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setFileMd5(fileMd5);
            mediaFile.setUploadTime(LocalDateTime.now());

            if (userId != null) {
                mediaFile.setUserId(userId);
            }

            mediaFileMapper.insert(mediaFile);

            if (userId != null) {
                String cacheKey = "media:list:user:" + userId;
                redisTemplate.delete(cacheKey);
                System.out.println("Cache cleared: " + cacheKey);
            }

            return org.springframework.http.ResponseEntity.ok("Upload success");

        } catch (Exception e) {
            e.printStackTrace();
            return org.springframework.http.ResponseEntity.status(500).body("Upload failed: " + e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    @GetMapping("/list")
    public List<MediaFileVO> getList(@RequestParam(value = "userId", required = false) Long userId) {
        String cacheKey = "media:list:user:" + (userId == null ? "anon" : userId);

        // 尝试从 Redis 缓存读取
        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                System.out.println("命中 Redis 缓存，直接返回！");
                return objectMapper.readValue(json, new TypeReference<List<MediaFileVO>>(){});
            }
        } catch (Exception e) {
            System.err.println("Redis 读取失败: " + e.getMessage());
        }

        // 1. 查主表（轻量，无 TEXT 字段）
        if (userId == null) {
            return Collections.emptyList();
        }

        List<MediaFile> files = mediaFileMapper.selectList(
            new LambdaQueryWrapper<MediaFile>()
                .eq(MediaFile::getUserId, userId)
                .orderByDesc(MediaFile::getUploadTime)
        );

        if (files.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 批量查询状态（避免 N+1）
        List<Long> mediaIds = files.stream().map(MediaFile::getId).collect(Collectors.toList());

        List<MediaAiAnalysis> analysisList = aiAnalysisMapper.selectBatchIds(mediaIds);
        List<MediaTranscription> transcriptionList = transcriptionMapper.selectBatchIds(mediaIds);

        // 3. 构建状态映射（只映射状态，不映射 TEXT）
        Map<Long, String> aiStatusMap = analysisList.stream()
            .collect(Collectors.toMap(MediaAiAnalysis::getMediaId, MediaAiAnalysis::getStatus));
        Map<Long, String> transcriptStatusMap = transcriptionList.stream()
            .collect(Collectors.toMap(MediaTranscription::getMediaId, MediaTranscription::getStatus));

        // 4. 组装 VO（只包含状态字段，不包含 TEXT 内容）
        List<MediaFileVO> voList = files.stream().map(file -> {
            MediaFileVO vo = new MediaFileVO();
            BeanUtils.copyProperties(file, vo);
            vo.setAiStatus(aiStatusMap.getOrDefault(file.getId(), "NONE"));
            vo.setTranscriptStatus(transcriptStatusMap.getOrDefault(file.getId(), "NONE"));
            return vo;
        }).collect(Collectors.toList());

        // 5. 写入 Redis 缓存
        try {
            String jsonToWrite = objectMapper.writeValueAsString(voList);
            redisTemplate.opsForValue().set(cacheKey, jsonToWrite, 30, TimeUnit.MINUTES);
            System.out.println("已写入 Redis 缓存（VO 格式，不含 TEXT）");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return voList;
    }

    //删除接口
    @DeleteMapping("/delete")
    public String delete(@RequestParam("id") Long id,
                         @RequestParam(value = "userId", required = false) Long userId) {

        MediaFile media = mediaFileMapper.selectById(id);
        if (media == null) return "文件不存在";

        if (userId != null && !media.getUserId().equals(userId)) {
            return "无权删除他人的文件";
        }

        if (media.getFilePath() != null && media.getFilePath().startsWith("http")) {
            minioUtils.removeFile(media.getFilePath());
        }

        // 级联删除子表数据
        aiAnalysisMapper.delete(new LambdaQueryWrapper<MediaAiAnalysis>().eq(MediaAiAnalysis::getMediaId, id));
        transcriptionMapper.delete(new LambdaQueryWrapper<MediaTranscription>().eq(MediaTranscription::getMediaId, id));

        // 删除主表
        mediaFileMapper.deleteById(id);

        if (media.getUserId() != null) {
            String cacheKey = "media:list:user:" + media.getUserId();
            redisTemplate.delete(cacheKey);
            System.out.println("缓存已清除: " + cacheKey);
        }

        return "删除成功";
    }
}
