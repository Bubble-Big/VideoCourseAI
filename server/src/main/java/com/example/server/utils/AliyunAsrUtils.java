package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.common.AiFailStage;
import com.example.server.exception.AiAnalysisException;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class AliyunAsrUtils {

    private final String apiKey;
    private final String asrModel;
    private final String asrUrl;

    private static final Logger log = LoggerFactory.getLogger(AliyunAsrUtils.class);

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .writeTimeout(600, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public AliyunAsrUtils(@Value("${ai.deepseek.api-key}") String apiKey,
                          @Value("${ai.asr.model}") String asrModel,
                          @Value("${ai.asr.url}") String asrUrl) {
        this.apiKey = apiKey;
        this.asrModel = asrModel;
        this.asrUrl = asrUrl;
    }

    public String audioToText(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) throw new AiAnalysisException("音频文件不存在: " + filePath, false, AiFailStage.FILE);

        int maxRetries = 3; // 最大重试次数
        String lastError = "";

        for (int i = 0; i < maxRetries; i++) {
            try {
                log.info("🎤 [ASR] 上传中 (第 {} 次尝试)...", i + 1);

                RequestBody requestBody = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", file.getName(),
                                RequestBody.create(file, MediaType.parse("application/octet-stream")))
                        // 【核心修改】换成电信的大模型，更稳，准确率更高
                        .addFormDataPart("model", asrModel)
                        .build();

                Request request = new Request.Builder()
                        .url(asrUrl)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .post(requestBody)
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String resultJson = response.body().string();
                        JSONObject jsonObject = JSON.parseObject(resultJson);
                        String text = jsonObject.getString("text");
                        if (text == null || text.isBlank()) {
                            // 无语音内容：返回空串（成功但无内容），由上层落库受控文案并短路 LLM
                            log.info("🎤 [ASR] 未识别到语音内容，返回空文本");
                            return "";
                        }
                        return text;
                    } else {
                        // 如果是 500 错误，记录并重试
                        String errBody = response.body() != null ? response.body().string() : "";
                        lastError = "HTTP " + response.code() + ": " + errBody;
                        log.warn("⚠️ ASR 失败 ({}/{}): {}", i + 1, maxRetries, lastError);

                        // 遇到 500/502/503 或 408/429 等服务端错误，指数退避重试
                        int code = response.code();
                        if (code >= 500 || code == 408 || code == 429) {
                            long backoffMs = 1_000L << i;   // 指数退避：i=0→1s, i=1→2s, i=2→4s
                            log.info("🎤 [ASR] 触发退避，等待 {}ms 后重试", backoffMs);
                            Thread.sleep(backoffMs);
                            continue;
                        } else {
                            // 如果是 400/401 等客户端错误，直接抛出不重试
                            throw new AiAnalysisException("ASR 识别失败: " + lastError, false, AiFailStage.ASR);
                        }
                    }
                }
            } catch (IOException e) {
                lastError = e.getMessage();
                log.warn("⚠️ 网络异常 ({}/{}): {}", i + 1, maxRetries, lastError);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "线程中断: " + e.getMessage();
                break;
            }
        }

        throw new AiAnalysisException("ASR 最终失败（已重试 3 次）: " + lastError, true, AiFailStage.ASR);
    }
}