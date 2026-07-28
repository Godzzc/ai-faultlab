package com.faultlab.backend.scenario.mq;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.trace.annotation.TraceSpan;
import org.springframework.stereotype.Service;

@Service
public class MqBacklogConsumerHandler {

    private final MetricService metricService;

    public MqBacklogConsumerHandler(MetricService metricService) {
        this.metricService = metricService;
    }

    @TraceSpan(operationName = "mq.consume.order", component = "RabbitMQ")
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
