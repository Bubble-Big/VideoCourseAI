package com.example.server.controller;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.common.AiStatus;
import com.example.server.common.ErrorCode;
import com.example.server.common.Result;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AiService;
import com.example.server.service.ContentTaskGate;
import com.example.server.service.RateLimitService;
import com.example.server.strategy.AiAnalysisStrategy;
import com.example.server.utils.AnalysisTaskKeys;
import com.example.server.utils.FfmpegUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/debug")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class DebugController {

    private final MediaFileMapper mediaFileMapper;
    private final AiAnalysisStrategy aiAnalysisStrategy;
    private final AiService aiService;
    private final StringRedisTemplate redisTemplate;
    private final org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;
    private final RateLimitService rateLimitService;
    private final ContentTaskGate contentTaskGate;

    @Value("${content.gate.enabled:true}")
    private boolean gateEnabled;

    public DebugController(MediaFileMapper mediaFileMapper,
                           @Qualifier("defaultAiStrategy") AiAnalysisStrategy aiAnalysisStrategy,
                           AiService aiService,
                           StringRedisTemplate redisTemplate,
                           org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate,
                           RateLimitService rateLimitService,
                           ContentTaskGate contentTaskGate) {
        this.mediaFileMapper = mediaFileMapper;
        this.aiAnalysisStrategy = aiAnalysisStrategy;
        this.aiService = aiService;
        this.redisTemplate = redisTemplate;
        this.rocketMQTemplate = rocketMQTemplate;
        this.rateLimitService = rateLimitService;
        this.contentTaskGate = contentTaskGate;
    }

    // AI总结接口(幂等键 + 限流 + MQ)
    @GetMapping("/ai")
    public Result<String> aiAnalyze(@RequestParam Long id) {
        MediaFile file = mediaFileMapper.selectById(id);
        if (file == null) throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在，请检查后重试");

        // 幂等：任务已在后台运行 → 不重复投递，返回成功让前端轮询等待结果
        String aiSt = file.getAiStatus();
        if (AiStatus.PENDING.name().equals(aiSt) || AiStatus.PROCESSING.name().equals(aiSt)) {
            return Result.ok("任务已在后台运行");
        }

        // 提交侧幂等键：内容级（contentHash），原子抢占；抢不到说明并发提交中，吞掉重复投递
        String contentHash = AnalysisTaskKeys.normalizeContentHash(id, file.getFileMd5());
        boolean accepted = gateEnabled
                ? contentTaskGate.tryMarkSubmitting(contentHash, id)
                : tryMarkSubmittingLegacy(contentHash, id);

        if (!accepted) {
            return Result.ok("任务提交中，请稍候");
        }

        String userIdKey = (file.getUserId() == null) ? "anon" : String.valueOf(file.getUserId());
        // 记录变更前的状态与旧结果，用于 MQ 投递失败时回滚，避免任务卡死在 PENDING
        String prevAiStatus = file.getAiStatus();
        String prevAiSummary = file.getAiSummary();
        try {
            // 双层限流：用户级 + 全局级（真超限 429，Redis 异常 503）
            rateLimitService.requireAiQuota(file.getUserId());

            //更新状态：投递 MQ 进入 PENDING；清空旧结果避免残留；记录首次触发时间 + 重置尝试计数
            file.setAiStatus(AiStatus.PENDING.name());
            file.setAiSummary(null);
            file.setAiProcessAt(LocalDateTime.now());
            file.setAiAttempts(0);
            mediaFileMapper.updateById(file);
            redisTemplate.delete("media:list:user:" + userIdKey);

            //发送消息（携带内容指纹，消费侧用 contentHash 做内容级锁 / 幂等）
            AnalysisTaskMsg msg = new AnalysisTaskMsg(id, "START_ANALYSIS", contentHash);
            rocketMQTemplate.convertAndSend("video-analysis-topic", msg);

            return Result.ok("任务已投递至 RocketMQ");

        } catch (RuntimeException e) {
            // 任何失败（限流超限 / 状态冲突 / 发 MQ 异常）：回滚幂等键 + 回滚 aiStatus/aiSummary，允许稍后重试。
            // 否则发 MQ 失败后 aiStatus 已落库 PENDING，前置幂等校验会误判「任务已在运行」，任务永久卡死。
            file.setAiStatus(prevAiStatus);
            file.setAiSummary(prevAiSummary);
            mediaFileMapper.updateById(file);
            redisTemplate.delete("media:list:user:" + userIdKey);

            if (gateEnabled) {
                contentTaskGate.rollbackSubmitting(contentHash);
            } else {
                redisTemplate.delete(AnalysisTaskKeys.active(contentHash));
            }
            throw e;
        }
    }

    /**
     * 旧版提交幂等键实现（向后兼容，待 gate 稳定后删除）
     */
    private boolean tryMarkSubmittingLegacy(String contentHash, Long mediaId) {
        String activeKey = AnalysisTaskKeys.active(contentHash);
        Boolean result = redisTemplate.opsForValue()
                .setIfAbsent(activeKey, String.valueOf(mediaId), java.time.Duration.ofSeconds(30));
        return Boolean.TRUE.equals(result);
    }

    //纯文字提取接口
    @GetMapping("/transcribe")
    public Result<String> transcribe(@RequestParam Long id) {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在，请检查后重试");

        // 幂等：正在提取时不重复提交，返回成功让前端轮询等待结果
        if (AiStatus.PROCESSING.name().equals(mediaFile.getTranscriptStatus())) {
            return Result.ok("任务已在后台运行");
        }

        // 文字提取配额：用户级 + 全局级双层限流
        rateLimitService.requireTranscribeQuota(mediaFile.getUserId());

        // 更新状态为 PROCESSING，并失效缓存让前端立即感知
        mediaFile.setTranscriptStatus(AiStatus.PROCESSING.name());
        mediaFileMapper.updateById(mediaFile);
        String userIdKey = (mediaFile.getUserId() == null) ? "anon" : String.valueOf(mediaFile.getUserId());
        redisTemplate.delete("media:list:user:" + userIdKey);

        // 调用异步服务
        aiService.asyncTranscribe(id);

        return Result.ok("提取任务已后台运行");
    }

    //下载音频接口
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam Long id) throws IOException {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) return ResponseEntity.notFound().build();

        String inputPath = mediaFile.getFilePath();

        if (!inputPath.startsWith("http")) {
            if (!new File(inputPath).exists()) return ResponseEntity.notFound().build();
        }

        String outputMp3Path = System.getProperty("java.io.tmpdir") + File.separator + "download_" + UUID.randomUUID() + ".mp3";
        System.out.println("⬇ 下载请求，正在从源地址转码音频: " + inputPath);

        boolean success = FfmpegUtils.extractAudio(inputPath, outputMp3Path);

        if (!success) return ResponseEntity.internalServerError().build();

        File mp3File = new File(outputMp3Path);
        Resource resource = new FileSystemResource(mp3File);

        String fileName = "audio.mp3";
        if (mediaFile.getFilename() != null) {
            fileName = mediaFile.getFilename().replaceAll("\\.[^.]+$", "") + ".mp3";
        }
        String encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(resource);
    }
}
