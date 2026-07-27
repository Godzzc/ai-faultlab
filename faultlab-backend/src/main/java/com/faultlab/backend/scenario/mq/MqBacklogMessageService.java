package com.faultlab.backend.scenario.mq;

import com.faultlab.backend.config.RabbitMqConfig.MqBacklogProperties;
import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.trace.annotation.TraceSpan;
import java.time.LocalDateTime;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class MqBacklogMessageService {

    private final RabbitTemplate rabbitTemplate;
    private final MqBacklogProperties mqBacklogProperties;
    private final MetricService metricService;

    public MqBacklogMessageService(
            RabbitTemplate rabbitTemplate,
            MqBacklogProperties mqBacklogProperties,
            MetricService metricService
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.mqBacklogProperties = mqBacklogProperties;
        this.metricService = metricService;
    }

    @TraceSpan(operationName = "mq.publish.order", component = "RabbitMQ")
    public void publishOrders(String experimentId, int messageCount, long consumerDelayMs) {
        for (int index = 0; index < messageCount; index++) {
            MqBacklogMessage message = new MqBacklogMessage(
                    experimentId,
                    index + 1,
                    consumerDelayMs,
                    LocalDateTime.now()
            );
            rabbitTemplate.convertAndSend(
                    mqBacklogProperties.getExchange(),
                    mqBacklogProperties.getRoutingKey(),
                    message
            );
        }
        metricService.recordMetric(experimentId, "publishCount", messageCount, "count", "RabbitMQ");
    }

    @TraceSpan(operationName = "mq.consume.order", component = "RabbitMQ")
    @RabbitListener(queues = "${faultlab.mq-backlog.queue}")
    public void consumeOrder(MqBacklogMessage message) {
        long startedAt = System.currentTimeMillis();
        sleep(message.getConsumerDelayMs());
        long totalConsumeMs = System.currentTimeMillis() - startedAt;

        metricService.recordMetric(message.getExperimentId(), "consumeCount", 1, "count", "RabbitMQ");
        metricService.recordMetric(message.getExperimentId(), "avgConsumeMs", totalConsumeMs, "ms", "RabbitMQ");
    }

    private void sleep(long consumerDelayMs) {
        if (consumerDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(consumerDelayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MQ backlog consumer interrupted", exception);
        }
    }
}
