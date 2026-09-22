package com.example.server.consumer;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.service.AiService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AI 分析死信兜底：监听默认死信队列 %DLQ%video-group，收到死信直接落 FAILED，
 * 避免 aiStatus 永久卡在 PENDING/PROCESSING、前端无限转圈。
 */
@Component
@RocketMQMessageListener(topic = "%DLQ%video-group", consumerGroup = "video-group-dlq")
public class VideoAnalysisDlqConsumer implements RocketMQListener<AnalysisTaskMsg> {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisDlqConsumer.class);

    private final AiService aiService;

    public VideoAnalysisDlqConsumer(AiService aiService) {
        this.aiService = aiService;
    }

    @Override
    public void onMessage(AnalysisTaskMsg msg) {
        Long mediaId = msg.getMediaId();
        log.warn("收到 AI 分析死信，兜底落失败, mediaId={}", mediaId);
        aiService.markFailedFinal(mediaId);
    }
}
