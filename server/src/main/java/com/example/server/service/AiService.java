package com.example.server.service;

import com.example.server.common.AiStatus;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.strategy.AiAnalysisStrategy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AiService {

    private final MediaFileMapper mediaFileMapper;
    private final AiAnalysisStrategy aiAnalysisStrategy;
    // 【关键】必须注入 Redis 工具！
    private final StringRedisTemplate redisTemplate;

    public AiService(MediaFileMapper mediaFileMapper,
                     @Qualifier("defaultAiStrategy") AiAnalysisStrategy aiAnalysisStrategy,
                     StringRedisTemplate redisTemplate) {
        this.mediaFileMapper = mediaFileMapper;
        this.aiAnalysisStrategy = aiAnalysisStrategy;
        this.redisTemplate = redisTemplate;
    }

    public void asyncAnalyze(Long mediaId) {
        System.out.println(" [线程池] 开始处理任务，ID: " + mediaId);

        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) return;

        // 进入处理态（不删缓存，避免中间态触发多余的 DB 查询）
        mediaFile.setAiStatus(AiStatus.PROCESSING.name());
        mediaFileMapper.updateById(mediaFile);

        try {
            // 1. 语音转文字
            String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
            mediaFile.setTranscriptText(text);
            mediaFile.setTranscriptStatus(isFailureText(text) ? AiStatus.FAILED.name() : AiStatus.SUCCESS.name());

            // 2. 智能总结
            String summary = aiAnalysisStrategy.generateSummary(mediaFile.getFilePath());
            mediaFile.setAiSummary(summary);
            mediaFile.setAiStatus(isFailureText(summary) ? AiStatus.FAILED.name() : AiStatus.SUCCESS.name());

            // 3. 保存数据库 (这一步你已经成功了)
            mediaFileMapper.updateById(mediaFile);


            // 1. 拼装缓存 Key (必须和 MediaController 里的逻辑完全一致！)
            // Controller 里是: "media:list:user:" + (userId == null ? "anon" : userId)
            String userIdStr = (mediaFile.getUserId() == null) ? "anon" : String.valueOf(mediaFile.getUserId());
            String cacheKey = "media:list:user:" + userIdStr;

            // 2. 狠狠地删除
            Boolean deleteResult = redisTemplate.delete(cacheKey);

            // 3. 打印显眼日志 (请在黑窗口找这句话！！！)
            if (Boolean.TRUE.equals(deleteResult)) {
                System.out.println(" [线程池] 缓存清除成功！Key: " + cacheKey);
            } else {
                System.out.println("⚠️ [线程池] 缓存不存在或清除失败 (但这不影响新数据写入)，Key: " + cacheKey);
            }

            System.out.println("✅ [线程池] 任务全部完成，前端轮询将在下一次命中新数据。");

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("❌ [线程池] 任务失败: " + e.getMessage());

            // 失败写入状态字段 + 错误详情，避免前端一直转圈
            mediaFile.setAiStatus(AiStatus.FAILED.name());
            mediaFile.setAiSummary("❌ 分析失败: " + e.getMessage());
            mediaFileMapper.updateById(mediaFile);

            // 失败也要删缓存，否则前端会一直转圈看不到“失败”两个字
            String userIdStr = (mediaFile.getUserId() == null) ? "anon" : String.valueOf(mediaFile.getUserId());
            redisTemplate.delete("media:list:user:" + userIdStr);
        }
    }



    //异步提取全文 (专门负责提取文字)
    @Async("aiTaskExecutor")
    public void asyncTranscribe(Long mediaId) {
        System.out.println(" [线程池] 开始全文提取任务，ID: " + mediaId);

        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) return;

        try {
            //只做语音转文字
            String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath());
            mediaFile.setTranscriptText(text);
            mediaFile.setTranscriptStatus(isFailureText(text) ? AiStatus.FAILED.name() : AiStatus.SUCCESS.name());

            //保存数据库
            mediaFileMapper.updateById(mediaFile);

            //强制删除 Redis 缓存
            String userIdStr = (mediaFile.getUserId() == null) ? "anon" : String.valueOf(mediaFile.getUserId());
            String cacheKey = "media:list:user:" + userIdStr;
            redisTemplate.delete(cacheKey);

            System.out.println(" [线程池] 全文提取完成，缓存已清除！Key: " + cacheKey);

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println(" [线程池] 提取失败: " + e.getMessage());

            // 失败写入状态字段 + 错误详情
            mediaFile.setTranscriptStatus(AiStatus.FAILED.name());
            mediaFile.setTranscriptText("❌ 提取失败: " + e.getMessage());
            mediaFileMapper.updateById(mediaFile);

            // 失败也删缓存，让前端能感知 FAILED
            String userIdStr = (mediaFile.getUserId() == null) ? "anon" : String.valueOf(mediaFile.getUserId());
            redisTemplate.delete("media:list:user:" + userIdStr);
        }
    }

    /**
     * 判断工具链返回的文本是否为错误结果。
     * <p>后端各环节失败时返回固定前缀的错误文案而非抛异常，
     * 这里按前缀精确匹配，避免误伤正常的中文转写文本。</p>
     */
    private boolean isFailureText(String text) {
        if (text == null || text.trim().isEmpty()) return true;
        String t = text.trim();
        return t.startsWith("❌")
                || t.startsWith("FFmpeg 转换失败")
                || t.startsWith("处理异常")
                || t.startsWith("AI request failed");
    }
}