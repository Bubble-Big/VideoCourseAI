package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.common.AiStatus;
import com.example.server.common.ErrorCode;
import com.example.server.common.Result;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.dto.TaskEvent;
import com.example.server.service.AiService;
import com.example.server.service.ContentTaskGate;
import com.example.server.service.RateLimitService;
import com.example.server.service.TaskEventService;
import com.example.server.strategy.AiAnalysisStrategy;
import com.example.server.utils.AnalysisTaskKeys;
import com.example.server.utils.FfmpegUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final TaskEventService taskEventService;

    public DebugController(MediaFileMapper mediaFileMapper,
                           @Qualifier("defaultAiStrategy") AiAnalysisStrategy aiAnalysisStrategy,
                           AiService aiService,
                           StringRedisTemplate redisTemplate,
                           org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate,
                           RateLimitService rateLimitService,
                           ContentTaskGate contentTaskGate,
                           TaskEventService taskEventService) {
        this.mediaFileMapper = mediaFileMapper;
        this.aiAnalysisStrategy = aiAnalysisStrategy;
        this.aiService = aiService;
        this.redisTemplate = redisTemplate;
        this.rocketMQTemplate = rocketMQTemplate;
        this.rateLimitService = rateLimitService;
        this.contentTaskGate = contentTaskGate;
        this.taskEventService = taskEventService;
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
        boolean accepted = contentTaskGate.tryMarkSubmitting(contentHash, id);

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
            // 问题 5：用户手动重试视为全新一轮，aiAttempts 与 compensationAttempts 均清零
            file.setAiAttempts(0);
            file.setCompensationAttempts(0);
            mediaFileMapper.update(null, new LambdaUpdateWrapper<MediaFile>()
                .eq(MediaFile::getId, file.getId())
                .set(MediaFile::getAiStatus, AiStatus.PENDING.name())
                .set(MediaFile::getAiSummary, null)
                .set(MediaFile::getAiProcessAt, LocalDateTime.now())
                .set(MediaFile::getAiAttempts, 0)
                .set(MediaFile::getCompensationAttempts, 0));
            redisTemplate.delete("media:list:user:" + userIdKey);

            //发送消息（携带内容指纹，消费侧用 contentHash 做内容级锁 / 幂等）
            AnalysisTaskMsg msg = new AnalysisTaskMsg(id, "START_ANALYSIS", contentHash);
            rocketMQTemplate.convertAndSend("video-analysis-topic", msg);

            // SSE 推送：PENDING
            taskEventService.publishAnalysis(id, AiStatus.PENDING.name(), null, null);

            return Result.ok("任务已投递至 RocketMQ");

        } catch (RuntimeException e) {
            // 任何失败（限流超限 / 状态冲突 / 发 MQ 异常）：回滚幂等键 + 回滚 aiStatus/aiSummary，允许稍后重试。
            // 否则发 MQ 失败后 aiStatus 已落库 PENDING，前置幂等校验会误判「任务已在运行」，任务永久卡死。

            // 使用 LambdaUpdateWrapper 只更新需要的字段，避免乐观锁冲突
            mediaFileMapper.update(null, new LambdaUpdateWrapper<MediaFile>()
                .eq(MediaFile::getId, file.getId())
                .set(MediaFile::getAiStatus, prevAiStatus)
                .set(MediaFile::getAiSummary, prevAiSummary));
            redisTemplate.delete("media:list:user:" + userIdKey);

            contentTaskGate.rollbackSubmitting(contentHash);
            throw e;
        }
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
        // 使用 LambdaUpdateWrapper 只更新需要的字段，避免乐观锁冲突
        mediaFileMapper.update(null, new LambdaUpdateWrapper<MediaFile>()
            .eq(MediaFile::getId, mediaFile.getId())
            .set(MediaFile::getTranscriptStatus, AiStatus.PROCESSING.name()));
        String userIdKey = (mediaFile.getUserId() == null) ? "anon" : String.valueOf(mediaFile.getUserId());
        redisTemplate.delete("media:list:user:" + userIdKey);

        // SSE 推送：transcription PROCESSING
        taskEventService.publishTranscription(id, AiStatus.PROCESSING.name(), null, null);

        // 调用异步服务
        aiService.asyncTranscribe(id);

        return Result.ok("提取任务已后台运行");
    }

    /**
     * SSE 任务事件流订阅端点
     *
     * @param id 媒体文件 ID
     * @param type 任务类型：ai（AI 分析）/ transcribe（文字提取）
     * @return SSE Emitter（text/event-stream）
     */
    @GetMapping(value = "/task-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeTaskEvents(@RequestParam Long id, @RequestParam String type) {
        // 验证参数
        if (!"ai".equals(type) && !"transcribe".equals(type)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "type 参数必须为 ai 或 transcribe");
        }

        // 查询当前状态
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }

        // 构建初始事件
        TaskEvent initialEvent;
        if ("ai".equals(type)) {
            String state = mediaFile.getAiStatus() != null ? mediaFile.getAiStatus() : AiStatus.NONE.name();
            initialEvent = TaskEvent.analysis(id, state, mediaFile.getAiSummary(), null);
        } else {
            String state = mediaFile.getTranscriptStatus() != null ? mediaFile.getTranscriptStatus() : AiStatus.NONE.name();
            initialEvent = TaskEvent.transcription(id, state, mediaFile.getTranscriptText(), null);
        }

        // 订阅并返回 SSE 连接
        return taskEventService.subscribe(id, type, initialEvent);
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
