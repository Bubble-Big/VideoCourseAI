package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.entity.MediaAiAnalysis;
import com.example.server.entity.MediaTranscription;
import com.example.server.common.AiStatus;
import com.example.server.common.ErrorCode;
import com.example.server.common.Result;
import com.example.server.exception.BusinessException;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.MediaAiAnalysisMapper;
import com.example.server.mapper.MediaTranscriptionMapper;
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
    private final MediaAiAnalysisMapper aiAnalysisMapper;
    private final MediaTranscriptionMapper transcriptionMapper;
    private final AiAnalysisStrategy aiAnalysisStrategy;
    private final AiService aiService;
    private final StringRedisTemplate redisTemplate;
    private final org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate;
    private final RateLimitService rateLimitService;
    private final ContentTaskGate contentTaskGate;
    private final TaskEventService taskEventService;

    public DebugController(MediaFileMapper mediaFileMapper,
                           MediaAiAnalysisMapper aiAnalysisMapper,
                           MediaTranscriptionMapper transcriptionMapper,
                           @Qualifier("defaultAiStrategy") AiAnalysisStrategy aiAnalysisStrategy,
                           AiService aiService,
                           StringRedisTemplate redisTemplate,
                           org.apache.rocketmq.spring.core.RocketMQTemplate rocketMQTemplate,
                           RateLimitService rateLimitService,
                           ContentTaskGate contentTaskGate,
                           TaskEventService taskEventService) {
        this.mediaFileMapper = mediaFileMapper;
        this.aiAnalysisMapper = aiAnalysisMapper;
        this.transcriptionMapper = transcriptionMapper;
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
    public Result<String> aiAnalyze(@RequestParam Long id,
                                    @RequestParam(value = "force", defaultValue = "false") boolean force) {
        MediaFile file = mediaFileMapper.selectById(id);
        if (file == null) throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在，请检查后重试");

        // 查询子表记录（按 media_id 查询，不是主键 id）
        MediaAiAnalysis analysis = aiAnalysisMapper.selectOne(
            new LambdaQueryWrapper<MediaAiAnalysis>().eq(MediaAiAnalysis::getMediaId, id)
        );

        // 幂等：任务已在后台运行 → 不重复投递，返回成功让前端轮询等待结果（force=true 时跳过幂等检查）
        if (!force && analysis != null) {
            String aiSt = analysis.getStatus();
            if (AiStatus.PENDING.name().equals(aiSt) || AiStatus.PROCESSING.name().equals(aiSt)) {
                return Result.ok("任务已在后台运行");
            }
        }

        // 提交侧幂等键：内容级（contentHash），原子抢占；抢不到说明并发提交中，吞掉重复投递
        String contentHash = AnalysisTaskKeys.normalizeContentHash(id, file.getFileMd5());
        boolean accepted = contentTaskGate.tryMarkSubmitting(contentHash, id);

        if (!accepted) {
            return Result.ok("任务提交中，请稍候");
        }

        String userIdKey = (file.getUserId() == null) ? "anon" : String.valueOf(file.getUserId());
        // 记录变更前的状态与旧结果，用于 MQ 投递失败时回滚，避免任务卡死在 PENDING
        String prevAiStatus = analysis != null ? analysis.getStatus() : null;
        String prevAiSummary = analysis != null ? analysis.getSummary() : null;
        try {
            // 双层限流：用户级 + 全局级（真超限 429，Redis 异常 503）
            rateLimitService.requireAiQuota(file.getUserId());

            // 更新子表状态：投递 MQ 进入 PENDING；清空旧结果避免残留；记录首次触发时间 + 重置尝试计数
            if (analysis == null) {
                analysis = new MediaAiAnalysis();
                analysis.setMediaId(id);
                analysis.setStatus(AiStatus.PENDING.name());
                analysis.setSummary(null);
                analysis.setProcessAt(LocalDateTime.now());
                analysis.setAttempts(0);
                analysis.setCompensationAttempts(0);
                analysis.setRetryCount(1);
                aiAnalysisMapper.insert(analysis);
            } else {
                Integer currentRetryCount = (analysis.getRetryCount() == null ? 0 : analysis.getRetryCount());
                int updated = aiAnalysisMapper.update(null, new LambdaUpdateWrapper<MediaAiAnalysis>()
                    .eq(MediaAiAnalysis::getMediaId, id)
                    .set(MediaAiAnalysis::getStatus, AiStatus.PENDING.name())
                    .set(MediaAiAnalysis::getSummary, null)
                    .set(MediaAiAnalysis::getProcessAt, LocalDateTime.now())
                    .set(MediaAiAnalysis::getAttempts, 0)
                    .set(MediaAiAnalysis::getCompensationAttempts, 0)
                    .set(MediaAiAnalysis::getRetryCount, currentRetryCount + 1));

                if (updated == 0) {
                    // 更新失败：记录已被其他操作修改，重新查询最新状态
                    MediaAiAnalysis latest = aiAnalysisMapper.selectById(id);
                    contentTaskGate.rollbackSubmitting(contentHash);

                    if (latest != null && AiStatus.SUCCESS.name().equals(latest.getStatus())) {
                        // 补偿调度器或其他操作已完成分析 → 用户尚未看到结果
                        // 直接返回成功，让前端 SSE 推送结果，用户体验流畅
                        redisTemplate.delete("media:list:user:" + userIdKey);
                        taskEventService.publishAnalysis(id, latest.getStatus(), latest.getSummary(), null);
                        return Result.ok("分析已完成");
                    } else if (latest != null && AiStatus.PROCESSING.name().equals(latest.getStatus())) {
                        // 任务还在处理中，无需重复提交
                        return Result.ok("任务已在后台运行");
                    } else {
                        // 其他状态（FAILED/PENDING）或记录不存在 → 提示冲突
                        throw new BusinessException(ErrorCode.CONFLICT, "文件状态已变更，请刷新后重试");
                    }
                }
            }

            redisTemplate.delete("media:list:user:" + userIdKey);

            //发送消息（携带内容指纹 + force 标记，消费侧用 contentHash 做内容级锁 / 幂等）
            AnalysisTaskMsg msg = new AnalysisTaskMsg(id, "START_ANALYSIS", contentHash, force);
            rocketMQTemplate.convertAndSend("video-analysis-topic", msg);

            // SSE 推送：PENDING
            taskEventService.publishAnalysis(id, AiStatus.PENDING.name(), null, null);

            return Result.ok("任务已投递至 RocketMQ");

        } catch (RuntimeException e) {
            // 任何失败（限流超限 / 状态冲突 / 发 MQ 异常）：回滚幂等键 + 回滚子表状态/结果，允许稍后重试。
            // 否则发 MQ 失败后状态已落库 PENDING，前置幂等校验会误判「任务已在运行」，任务永久卡死。

            // 回滚子表状态
            if (analysis != null) {
                aiAnalysisMapper.update(null, new LambdaUpdateWrapper<MediaAiAnalysis>()
                    .eq(MediaAiAnalysis::getMediaId, id)
                    .set(MediaAiAnalysis::getStatus, prevAiStatus)
                    .set(MediaAiAnalysis::getSummary, prevAiSummary));
            }
            redisTemplate.delete("media:list:user:" + userIdKey);

            contentTaskGate.rollbackSubmitting(contentHash);
            throw e;
        }
    }

    //纯文字提取接口
    @GetMapping("/transcribe")
    public Result<String> transcribe(@RequestParam Long id,
                                     @RequestParam(value = "force", defaultValue = "false") boolean force) {
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在，请检查后重试");

        // 查询子表记录
        MediaTranscription transcription = transcriptionMapper.selectOne(
            new LambdaQueryWrapper<MediaTranscription>().eq(MediaTranscription::getMediaId, id)
        );

        // 幂等：正在提取时不重复提交，返回成功让前端轮询等待结果（force=true 时跳过幂等检查）
        if (!force && transcription != null && AiStatus.PROCESSING.name().equals(transcription.getStatus())) {
            return Result.ok("任务已在后台运行");
        }

        // 文字提取配额：用户级 + 全局级双层限流
        rateLimitService.requireTranscribeQuota(mediaFile.getUserId());

        Integer currentRetryCount = (transcription != null && transcription.getRetryCount() != null)
            ? transcription.getRetryCount() : 0;
        String userIdKey = (mediaFile.getUserId() == null) ? "anon" : String.valueOf(mediaFile.getUserId());

        // 更新子表状态为 PROCESSING，并失效缓存让前端立即感知
        // 同时递增 retryCount 用于补偿调度器检测冲突，重置补偿计数
        if (transcription == null) {
            transcription = new MediaTranscription();
            transcription.setMediaId(id);
            transcription.setStatus(AiStatus.PROCESSING.name());
            transcription.setTranscriptText(null);
            transcription.setProcessAt(LocalDateTime.now());
            transcription.setAttempts(0);
            transcription.setCompensationAttempts(0);
            transcription.setRetryCount(1);
            transcriptionMapper.insert(transcription);
        } else {
            int updated = transcriptionMapper.update(null, new LambdaUpdateWrapper<MediaTranscription>()
                .eq(MediaTranscription::getMediaId, id)
                .set(MediaTranscription::getStatus, AiStatus.PROCESSING.name())
                .set(MediaTranscription::getTranscriptText, null)
                .set(MediaTranscription::getProcessAt, LocalDateTime.now())
                .set(MediaTranscription::getCompensationAttempts, 0)
                .set(MediaTranscription::getRetryCount, currentRetryCount + 1));

            if (updated == 0) {
                // 更新失败：记录可能已被其他操作修改，重新查询最新状态
                MediaTranscription latest = transcriptionMapper.selectOne(
                    new LambdaQueryWrapper<MediaTranscription>().eq(MediaTranscription::getMediaId, id)
                );

                if (latest != null && AiStatus.SUCCESS.name().equals(latest.getStatus())) {
                    // 补偿调度器或其他操作已完成文字提取 → 直接返回成功
                    redisTemplate.delete("media:list:user:" + userIdKey);
                    taskEventService.publishTranscription(id, latest.getStatus(), latest.getTranscriptText(), null);
                    return Result.ok("提取已完成");
                } else if (latest != null && AiStatus.PROCESSING.name().equals(latest.getStatus())) {
                    // 任务还在处理中，无需重复提交
                    return Result.ok("任务已在后台运行");
                } else {
                    // 其他状态（FAILED/NONE）或记录不存在 → 提示冲突
                    throw new BusinessException(ErrorCode.CONFLICT, "文件状态已变更，请刷新后重试");
                }
            }
        }

        redisTemplate.delete("media:list:user:" + userIdKey);

        // SSE 推送：transcription PROCESSING
        taskEventService.publishTranscription(id, AiStatus.PROCESSING.name(), null, null);

        // 调用异步服务（传递 force 参数）
        aiService.asyncTranscribe(id, force);

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
    public SseEmitter subscribeTaskEvents(@RequestParam Long id,
                                          @RequestParam String type,
                                          @RequestParam(required = false) Long userId) {
        // 验证参数
        if (!"ai".equals(type) && !"transcribe".equals(type)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "type 参数必须为 ai 或 transcribe");
        }

        // 查询当前状态
        MediaFile mediaFile = mediaFileMapper.selectById(id);
        if (mediaFile == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }

        // P0：校验当前用户是否有权访问该文件
        if (userId != null && mediaFile.getUserId() != null && !mediaFile.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该文件的任务事件");
        }

        // 构建初始事件
        TaskEvent initialEvent;
        if ("ai".equals(type)) {
            MediaAiAnalysis analysis = aiAnalysisMapper.selectOne(
                new LambdaQueryWrapper<MediaAiAnalysis>().eq(MediaAiAnalysis::getMediaId, id)
            );
            String state = (analysis != null && analysis.getStatus() != null) ? analysis.getStatus() : AiStatus.NONE.name();
            String summary = (analysis != null) ? analysis.getSummary() : null;
            initialEvent = TaskEvent.analysis(id, state, summary, null);
        } else {
            MediaTranscription transcription = transcriptionMapper.selectOne(
                new LambdaQueryWrapper<MediaTranscription>().eq(MediaTranscription::getMediaId, id)
            );
            String state = (transcription != null && transcription.getStatus() != null) ? transcription.getStatus() : AiStatus.NONE.name();
            String text = (transcription != null) ? transcription.getTranscriptText() : null;
            initialEvent = TaskEvent.transcription(id, state, text, null);
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
