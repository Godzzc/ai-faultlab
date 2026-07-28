package com.faultlab.backend.scenario.mq;

import com.faultlab.backend.metric.service.MetricService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MqBacklogConsumerHandlerTests {

    private final MetricService metricService = mock(MetricService.class);
    private final MqBacklogConsumerHandler consumerHandler = new MqBacklogConsumerHandler(metricService);

    @Test
    void shouldRecordMetrics() {
        consumerHandler.consumeOrder(new MqBacklogMessage("exp-1", 1, 0, LocalDateTime.now()));

        verify(metricService).recordMetric("exp-1", "consumeCount", 1, "count", "RabbitMQ");
        ArgumentCaptor<Number> avgConsumeMsCaptor = ArgumentCaptor.forClass(Number.class);
        verify(metricService).recordMetric(eq("exp-1"), eq("avgConsumeMs"), avgConsumeMsCaptor.capture(), eq("ms"), eq("RabbitMQ"));
        assertThat(avgConsumeMsCaptor.getValue().longValue()).isGreaterThanOrEqualTo(0L);
    }
}
