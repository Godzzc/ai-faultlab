package com.faultlab.backend.scenario.mq;

import com.faultlab.backend.config.RabbitMqConfig.MqBacklogProperties;
import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.trace.annotation.TraceSpan;
import com.faultlab.backend.trace.context.TraceContextHolder;
import com.faultlab.backend.trace.context.TraceContextPropagator;
import com.faultlab.backend.trace.context.TraceContextSnapshot;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Service;

@Service
public class MqBacklogMessageService {

    private final RabbitTemplate rabbitTemplate;
    private final MqBacklogProperties mqBacklogProperties;
    private final MetricService metricService;
    private final MqBacklogConsumerHandler mqBacklogConsumerHandler;

    public MqBacklogMessageService(
            RabbitTemplate rabbitTemplate,
            MqBacklogProperties mqBacklogProperties,
            MetricService metricService,
            MqBacklogConsumerHandler mqBacklogConsumerHandler
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.mqBacklogProperties = mqBacklogProperties;
        this.metricService = metricService;
        this.mqBacklogConsumerHandler = mqBacklogConsumerHandler;
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
                    message,
                    TraceContextPropagator.messagePostProcessor()
            );
        }
        metricService.recordMetric(experimentId, "publishCount", messageCount, "count", "RabbitMQ");
    }

    @RabbitListener(queues = "${faultlab.mq-backlog.queue}")
    public void consumeOrder(MqBacklogMessage message, @Headers Map<String, Object> headers) {
        TraceContextSnapshot snapshot = TraceContextPropagator.fromHeaders(headers);
        try {
            TraceContextPropagator.restore(snapshot);
            mqBacklogConsumerHandler.consumeOrder(message);
        } finally {
            TraceContextHolder.clear();
        }
    }
}
