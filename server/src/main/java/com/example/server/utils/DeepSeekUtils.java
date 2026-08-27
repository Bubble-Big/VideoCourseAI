package com.example.server.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.server.exception.AiAnalysisException;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class DeepSeekUtils {

    private final String apiKey;
    private final String baseUrl;
    private final String model;

    /** 系统提示词：定义 AI 角色与输出格式 */
    private static final String SYSTEM_PROMPT = """
    # Role
    你是一位拥有认知心理学背景的资深信息架构师。你的专长是从杂乱的语音转录文本中提取高价值信息，并进行逻辑重构。

    # Input Context
    用户将提供一段由视频生成的语音识别（ASR）文本。文本可能包含口语废话、重复、语气词或识别错误。

    # Goals
    请忽略文本中的噪音，对内容进行深度降噪和逻辑精炼，最终输出一份结构清晰、语气专业的分析报告。

    # Constraints
    1. **必须**严格遵守下方的输出格式。
    2. 语气保持客观、理性、犀利。
    3. 如果文本内容过短或无意义，直接输出"无法提取有效信息"。
    4. 禁止输出任何开场白或结束语（如"好的，我来分析..."），直接输出 Markdown 内容。

    # Output Format (Markdown)
    请严格按照以下模块输出：

    ## 核心摘要
    （精简概括视频到底讲了什么，直击本质，全面贴切，但要一针见血地概括视频主旨。）

    ## 深度洞察
    （提取 3-5 个核心观点，每个观点使用三级标题格式，如下所示：）

    ### 1. [这里提炼一个 4-8 字的强观点标题]
    不要复述原话。请用专业的语言解释这个观点背后的逻辑、动因或对观众的启示。分析要犀利，直击本质。

    ### 2. [第二个强观点标题]
    （此处填写对应的深度分析...）

    ### 3. [第三个强观点标题]
    （此处填写对应的深度分析...）(后续标题和分析同理)

    ## 原始内容精选
    > "引用视频中原本的最有价值的一句原话（修正错别字后）"
    > "引用第二句有价值的原话"（如果有，不一定必须精选，后续同理，但原始内容精选最多三个）

    ## 🏷️ 领域标签
    #标签1 #标签2 #标签3
    """;

    private static final Logger log = LoggerFactory.getLogger(DeepSeekUtils.class);

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public DeepSeekUtils(@Value("${ai.deepseek.api-key}") String apiKey,
                         @Value("${ai.deepseek.base-url}") String baseUrl,
                         @Value("${ai.deepseek.model}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
    }


    /**
     * 对 ASR 转录文本进行智能总结。
     * <p>内部调用 {@link #buildChatRequest} 拼装 prompt，再由 {@link #callWithRetry} 发请求。
     */
    public String analyzeContent(String content) {
        String url = baseUrl + "/chat/completions";
        return callWithRetry(() -> buildChatRequest(url, content));
    }

    // ======================== Prompt 拼装（纯数据转换） ========================

    /**
     * 将 system prompt + 用户文本拼装为 OkHttp Request。
     * <p>每次调用生成全新的 Request 对象（RequestBody 只能读一次，重试需要重建）。
     */
    private Request buildChatRequest(String url, String userContent) {
        JSONObject jsonBody = new JSONObject();
        jsonBody.put("model", model);
        jsonBody.put("stream", false);

        JSONArray messages = new JSONArray();
        messages.add(JSONObject.of("role", "system", "content", SYSTEM_PROMPT));
        messages.add(JSONObject.of("role", "user", "content", userContent));
        jsonBody.put("messages", messages);

        RequestBody body = RequestBody.create(
                jsonBody.toString(),
                MediaType.parse("application/json; charset=utf-8")
        );

        return new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();
    }

    // ======================== API 调用 + 重试（纯网络通信） ========================

    /**
     * 执行 OkHttp 请求，3 次重试：5xx/408/429 等 2s 重试，其余 4xx 不重试直接抛。
     * <p>通过 {@link Supplier} 获取 Request，重试时调用 Supplier 重新生成全新 Request。
     */
    private String callWithRetry(Supplier<Request> requestSupplier) {
        int maxRetries = 3;
        String lastError = "";

        for (int i = 0; i < maxRetries; i++) {
            try {
                Request request = requestSupplier.get();
                log.info("[DeepSeek] 请求中 (第 {} 次尝试)...", i + 1);

                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String resultJson = response.body().string();
                        JSONObject jsonObject = JSON.parseObject(resultJson);
                        JSONArray choices = jsonObject.getJSONArray("choices");
                        if (choices == null || choices.isEmpty()) {
                            // 200 却无 choices：确定性异常（被过滤/模型配置问题），判永久失败避免整链路重跑 3 次
                            throw new AiAnalysisException("DeepSeek 响应无有效内容", false);
                        }
                        String content = choices.getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content");
                        if (content == null || content.isBlank()) {
                            // 输入文本已非空，空 content 属服务侧确定性异常，判永久失败避免整链路重跑 3 次
                            throw new AiAnalysisException("DeepSeek 响应无有效内容", false);
                        }
                        return content;
                    } else {
                        String errBody = response.body() != null ? response.body().string() : "";
                        lastError = "HTTP " + response.code() + ": " + errBody;
                        log.warn("[DeepSeek] 失败 ({}/{}): {}", i + 1, maxRetries, lastError);

                        int code = response.code();
                        if (code >= 500 || code == 408 || code == 429) {
                            Thread.sleep(2000);
                            continue;
                        } else {
                            throw new AiAnalysisException("DeepSeek 请求被拒绝: " + lastError, false);
                        }
                    }
                }
            } catch (IOException e) {
                lastError = e.getMessage();
                log.warn("[DeepSeek] 网络异常 ({}/{}): {}", i + 1, maxRetries, lastError);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "retry interrupted: " + e.getMessage();
                break;
            }
        }

        throw new AiAnalysisException("DeepSeek 请求失败，已重试 " + maxRetries + " 次: " + lastError, true);
    }
}
