package com.example.server.service;

import com.example.server.entity.FailedAnalysisTask;
import com.example.server.exception.AiAnalysisException;
import com.example.server.mapper.FailedAnalysisTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 失败台账服务：只负责「记录一次 AI 分析永久失败」。
 * <p>写入失败仅记日志、不阻断主流程（对齐 DOVideo-AI 的 addSuppressed 策略）。</p>
 */
@Service
public class FailedAnalysisTaskService {

    private static final Logger log = LoggerFactory.getLogger(FailedAnalysisTaskService.class);

    private final FailedAnalysisTaskMapper failedAnalysisTaskMapper;

    public FailedAnalysisTaskService(FailedAnalysisTaskMapper failedAnalysisTaskMapper) {
        this.failedAnalysisTaskMapper = failedAnalysisTaskMapper;
    }

    /**
     * 记录一次失败：errorType 落失败阶段（ASR/LLM/FFMPEG/...），attempts 落真实补偿重试次数。
     *
     * @param mediaId  关联媒体
     * @param e        携带失败阶段语义的异常
     * @param attempts 补偿重试次数（0=首次失败未重试，N=补偿重试 N 次）
     */
    public void record(Long mediaId, AiAnalysisException e, int attempts) {
        try {
            FailedAnalysisTask task = new FailedAnalysisTask();
            task.setMediaId(mediaId);
            task.setErrorType(e.getStage().name());
            task.setErrorMsg(truncate(e.getMessage()));
            task.setAttempts(attempts);
            failedAnalysisTaskMapper.insert(task);
        } catch (Exception ex) {
            log.warn("失败台账写入失败, mediaId={}, err={}", mediaId, ex.getMessage());
        }
    }

    private String truncate(String msg) {
        if (msg == null) {
            return null;
        }
        return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
    }
}
