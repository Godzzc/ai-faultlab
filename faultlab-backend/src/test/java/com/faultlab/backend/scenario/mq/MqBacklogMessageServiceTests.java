package com.faultlab.backend.scenario.mq;

import com.faultlab.backend.config.RabbitMqConfig.MqBacklogProperties;
import com.faultlab.backend.metric.service.MetricService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MqBacklogMessageServiceTests {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final MetricService metricService = mock(MetricService.class);
    private final MqBacklogProperties properties = properties();
    private final MqBacklogMessageService messageService = new MqBacklogMessageService(
            rabbitTemplate,
            properties,
            metricService
    );

    @Test
    void shouldPublishExpectedMessageCount() {
        messageService.publishOrders("exp-1", 3, 0);

        ArgumentCaptor<MqBacklogMessage> messageCaptor = ArgumentCaptor.forClass(MqBacklogMessage.class);
        verify(rabbitTemplate, times(3)).convertAndSend(
                eq("faultlab.mq.backlog.exchange"),
                eq("faultlab.mq.backlog.order"),
                messageCaptor.capture()
        );
        assertThat(messageCaptor.getAllValues()).hasSize(3);
        assertThat(messageCaptor.getAllValues().get(0).getExperimentId()).isEqualTo("exp-1");
        verify(metricService).recordMetric("exp-1", "publishCount", 3, "count", "RabbitMQ");
    }

    @Test
    void shouldRecordMetrics() {
        messageService.consumeOrder(new MqBacklogMessage("exp-1", 1, 0, LocalDateTime.now()));

        verify(metricService).recordMetric("exp-1", "consumeCount", 1, "count", "RabbitMQ");
        ArgumentCaptor<Number> avgConsumeMsCaptor = ArgumentCaptor.forClass(Number.class);
        verify(metricService).recordMetric(eq("exp-1"), eq("avgConsumeMs"), avgConsumeMsCaptor.capture(), eq("ms"), eq("RabbitMQ"));
        assertThat(avgConsumeMsCaptor.getValue().longValue()).isGreaterThanOrEqualTo(0L);
    }

    private MqBacklogProperties properties() {
        MqBacklogProperties mqBacklogProperties = new MqBacklogProperties();
        mqBacklogProperties.setExchange("faultlab.mq.backlog.exchange");
        mqBacklogProperties.setQueue("faultlab.mq.backlog.queue");
        mqBacklogProperties.setRoutingKey("faultlab.mq.backlog.order");
        return mqBacklogProperties;
    }
}
