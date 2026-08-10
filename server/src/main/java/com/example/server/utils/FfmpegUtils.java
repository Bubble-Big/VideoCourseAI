package com.example.server.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * FFmpeg 音频提取工具类，统一项目中所有 FFmpeg 调用。
 *
 * <p>等价命令行：
 * <pre>{@code
 * ffmpeg -y -i <input> -vn -acodec libmp3lame -q:a 2 <output>
 * }</pre>
 */
public class FfmpegUtils {

    /** 默认音频编码器 */
    private static final String AUDIO_CODEC = "libmp3lame";
    /** 默认音频质量（0-9，越小越好，2 为高质量） */
    private static final String AUDIO_QUALITY = "2";
    /** 默认超时时间（分钟） */
    private static final long DEFAULT_TIMEOUT_MINUTES = 15;

    /**
     * 从视频提取音频为 MP3（默认 15 分钟超时）。
     *
     * @param inputPath  输入视频路径，支持本地文件路径和 HTTP URL
     * @param outputPath 输出 MP3 文件路径
     * @return true 成功，false 失败
     */
    public static boolean extractAudio(String inputPath, String outputPath) {
        return extractAudio(inputPath, outputPath, DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 从视频提取音频为 MP3（自定义超时）。
     *
     * @param inputPath  输入视频路径，支持本地文件路径和 HTTP URL
     * @param outputPath 输出 MP3 文件路径
     * @param timeout    超时时间
     * @param unit       超时时间单位
     * @return true 成功，false 失败
     */
    public static boolean extractAudio(String inputPath, String outputPath, long timeout, TimeUnit unit) {
        Process process = null;
        try {
            List<String> command = buildCommand(inputPath, outputPath);

            ProcessBuilder pb = new ProcessBuilder(command);
            // 合并 stderr → stdout，避免管道缓冲区满导致死锁
            pb.redirectErrorStream(true);
            // 输出继承父进程（IDEA 控制台可见），不占用 PIPE 缓冲区
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            process = pb.start();

            boolean finished = process.waitFor(timeout, unit);

            if (finished) {
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    System.err.println("⚠️ FFmpeg 退出码异常: " + exitCode + "，输入: " + inputPath);
                }
                return exitCode == 0;
            } else {
                // 超时：强制终止子进程
                System.err.println("⏰ FFmpeg 超时（>" + timeout + " " + unit + "），强制终止。输入: " + inputPath);
                process.destroyForcibly();
                return false;
            }

        } catch (Exception e) {
            System.err.println("❌ FFmpeg 执行异常: " + e.getMessage());
            e.printStackTrace();
            return false;

        } finally {
            // 兜底：确保子进程不会变成僵尸
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 构建 FFmpeg 命令。
     *
     * <pre>{@code
     * ffmpeg -y -i <input> -vn -acodec libmp3lame -q:a 2 <output>
     * }</pre>
     */
    private static List<String> buildCommand(String inputPath, String outputPath) {
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-y");                // 静默覆盖已存在的输出文件
        command.add("-i");
        command.add(inputPath);
        command.add("-vn");               // 丢弃视频流
        command.add("-acodec");
        command.add(AUDIO_CODEC);         // MP3 编码器
        command.add("-q:a");
        command.add(AUDIO_QUALITY);       // 音频质量
        command.add(outputPath);
        return command;
    }
}
