package com.example.server.strategy.impl;

import com.example.server.common.AiFailStage;
import com.example.server.exception.AiAnalysisException;
import com.example.server.strategy.AiAnalysisStrategy;
import com.example.server.utils.AliyunAsrUtils;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.FfmpegUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.UUID;

@Component("defaultAiStrategy")
public class AliyunDeepSeekStrategy implements AiAnalysisStrategy {

    private static final Logger log = LoggerFactory.getLogger(AliyunDeepSeekStrategy.class);

    private final AliyunAsrUtils aliyunAsrUtils;
    private final DeepSeekUtils deepSeekUtils;

    public AliyunDeepSeekStrategy(AliyunAsrUtils aliyunAsrUtils,
                                  DeepSeekUtils deepSeekUtils) {
        this.aliyunAsrUtils = aliyunAsrUtils;
        this.deepSeekUtils = deepSeekUtils;
    }

    @Override
    public String transcribe(String videoPath) {
        return processVideoToText(videoPath);
    }

    @Override
    public String generateSummaryFromText(String text) {
        return deepSeekUtils.analyzeContent("请对以下视频提取的文字进行总结，不需要废话，直接列出核心观点：\n" + text);
    }


    private String processVideoToText(String inputPath) {
        //简单检查
        if (inputPath == null || inputPath.isEmpty()) throw new AiAnalysisException("视频路径为空", false, AiFailStage.FILE);

        //如果是本地路径且不存在，报错；如果是 http 链接，跳过检查直接交给 FFmpeg
        if (!inputPath.startsWith("http")) {
            File localFile = new File(inputPath);
            if (!localFile.exists()) throw new AiAnalysisException("磁盘找不到文件: " + inputPath, false, AiFailStage.FILE);
        }

        //准备临时 MP3 路径 (放在系统临时目录下)
        String outputMp3Path = System.getProperty("java.io.tmpdir") + File.separator + "temp_" + UUID.randomUUID() + ".mp3";

        try {
            log.info("🎵 [AI策略] 正在处理视频源: {}", inputPath);

            // 3. 提取音频 (FFmpeg 原生支持 HTTP URL，这里直接传进去)
            boolean success = FfmpegUtils.extractAudio(inputPath, outputMp3Path);
            if (!success) throw new AiAnalysisException("FFmpeg 提取音频失败", true, AiFailStage.FFMPEG);

            // 4. 语音转文字
            String text = aliyunAsrUtils.audioToText(outputMp3Path);
            return text;

        } finally {
            // 5. 清理临时文件
            File mp3 = new File(outputMp3Path);
            if (mp3.exists()) mp3.delete();
        }
    }
}