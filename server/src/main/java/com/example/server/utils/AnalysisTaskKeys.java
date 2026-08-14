package com.example.server.utils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 分析任务相关的 Redis Key 生成与内容指纹（contentHash）标准化工具。
 * <p>理念：视频的「身份」= 内容指纹（MD5），而非数据库自增 id。
 * 锁 / 幂等 / 复用均以 contentHash 为身份，实现跨 mediaId / 跨用户的串行化与复用。</p>
 */
public final class AnalysisTaskKeys {

    private AnalysisTaskKeys() {}

    /** MD5 格式：32 位十六进制。 */
    private static final Pattern MD5_PATTERN = Pattern.compile("[a-fA-F0-9]{32}");

    /**
     * 标准化内容指纹：合法 MD5 统一小写返回；非法（历史数据 / 直传改造前上传）回退 media-{id}。
     */
    public static String normalizeContentHash(Long mediaId, String md5) {
        if (md5 != null && MD5_PATTERN.matcher(md5).matches()) {
            return md5.toLowerCase(Locale.ROOT);
        }
        return "media-" + mediaId;
    }

    /** 任务级分析锁：内容级，跨 mediaId 串行（同一内容只跑一次完整分析）。 */
    public static String analysisLock(String contentHash) {
        return "lock:analysis:" + contentHash;
    }

    /** 内容级上下文锁（转写）：同一内容只跑一次 ASR。 */
    public static String contextLock(String contentHash) {
        return "lock:analysis-context:" + contentHash;
    }

    /** 内容级转写归属：记录哪个 mediaId 已产出该内容的转写文本。 */
    public static String contextOwner(String contentHash) {
        return "analysis:context-owner:" + contentHash;
    }

    /** 内容级分析结果归属：记录哪个 mediaId 已产出该内容的完整分析（summary）。 */
    public static String completedOwner(String contentHash) {
        return "analysis:completed-owner:" + contentHash;
    }

    /** 内容级分析活跃标记（提交侧幂等键）：同一内容正在提交/分析时，重复提交直接拒绝。 */
    public static String active(String contentHash) {
        return "analysis:active:" + contentHash;
    }
}
