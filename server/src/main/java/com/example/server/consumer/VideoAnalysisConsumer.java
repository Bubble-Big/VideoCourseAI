package com.example.server.consumer;

import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.service.AiService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;

@Component
// 监听 "video-analysis-topic" 主题；maxReconsumeTimes=2 仅作防御（消息反序列化等异常仍会重投），
// 核心重试已下沉到 AiService 的 DB 状态机 + 定时补偿（见 AnalysisCompensationScheduler）
@RocketMQMessageListener(topic = "video-analysis-topic", consumerGroup = "video-group", maxReconsumeTimes = 2)
public class VideoAnalysisConsumer implements RocketMQListener<AnalysisTaskMsg> {

    private static final Logger log = LoggerFactory.getLogger(VideoAnalysisConsumer.class);

    private final AiService aiService;

    public VideoAnalysisConsumer(AiService aiService) {
        this.aiService = aiService;
    }

    @Override
    public void onMessage(AnalysisTaskMsg msg) {
        Long mediaId = msg.getMediaId();
        log.info("收到分析任务, mediaId={}", mediaId);
        try {
            // 只做触发派发：@Async 立即返回，监听线程快进快出，不执行重活
            aiService.asyncAnalyze(mediaId);
        } catch (RejectedExecutionException e) {
            // 线程池队列满：吞掉并正常 ACK，状态仍是 PENDING，交给定时补偿兜底
            log.warn("分析任务派发被拒绝（线程池过载），等待补偿重试, mediaId={}", mediaId);
        }
    }
}
