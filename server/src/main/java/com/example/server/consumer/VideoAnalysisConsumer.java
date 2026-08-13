package com.example.server.consumer;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.exception.AiAnalysisException;
import com.example.server.service.AiService;
import com.example.server.service.FailedAnalysisTaskService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
// 监听 "video-analysis-topic" 主题；maxReconsumeTimes=2（2 次重投 = 最多 3 次投递）
@RocketMQMessageListener(topic = "video-analysis-topic", consumerGroup = "video-group", maxReconsumeTimes = 2)
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisConsumer.class);

    private final AiService aiService;
    private final FailedAnalysisTaskService failedTaskService;

    public VideoAnalysisConsumer(AiService aiService,
                                 FailedAnalysisTaskService failedTaskService) {
        this.aiService = aiService;
        this.failedTaskService = failedTaskService;
    }

    @Override
    public void onMessage(AnalysisTaskMsg msg) {
        Long mediaId = msg.getMediaId();
        log.info("收到分析任务, mediaId={}", mediaId);

        try {
            // 同步消费：异常在此上抛，交给 RocketMQ 按 maxReconsumeTimes 重投
            aiService.asyncAnalyze(mediaId);
        } catch (AiAnalysisException e) {
            if (!e.isRetryable()) {
                // 永久失败：写台账（FAILED 状态已由 AiService.markFailed 落库），正常 ACK 不再重投
                log.warn("分析任务永久失败, mediaId={}, err={}", mediaId, e.getMessage());
                failedTaskService.record(mediaId, e);
                return;
            }
            // 瞬时失败：也写台账（记录本次失败），再上抛触发 RocketMQ 重投
            log.warn("分析任务瞬时失败，记录台账并等待 MQ 重投, mediaId={}, err={}", mediaId, e.getMessage());
            failedTaskService.record(mediaId, e);
            throw e;
        } catch (Exception e) {
            // 未预期异常：按可重试处理，触发重投
            log.error("分析任务消费异常, mediaId={}", mediaId, e);
            throw new RuntimeException("视频分析消费异常", e);
        }
    }
}
