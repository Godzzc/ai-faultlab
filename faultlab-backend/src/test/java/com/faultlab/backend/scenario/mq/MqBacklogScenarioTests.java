package com.faultlab.backend.scenario.mq;

import com.faultlab.backend.metric.service.MetricService;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MqBacklogScenarioTests {

    private final MqBacklogMessageService mqBacklogMessageService = mock(MqBacklogMessageService.class);
    private final MetricService metricService = mock(MetricService.class);
    private final MqBacklogScenario scenario = new MqBacklogScenario(mqBacklogMessageService, metricService);

    @Test
    void shouldUseDefaultParamsWhenParamsMissing() {
        scenario.execute("exp-1", null);

        verify(mqBacklogMessageService).publishOrders(
                "exp-1",
                MqBacklogScenario.DEFAULT_MESSAGE_COUNT,
                MqBacklogScenario.DEFAULT_CONSUMER_DELAY_MS
        );
    }

    @Test
    void shouldRecordMetrics() {
        scenario.execute("exp-1", Map.of("messageCount", 5, "consumerDelayMs", 10));

        verify(metricService).recordMetric("exp-1", "messageCount", 5, "count", "RabbitMQ");
        verify(metricService).recordMetric("exp-1", "consumerDelayMs", 10L, "ms", "RabbitMQ");
    }

    @Test
    void shouldNotBlockStartupForConsumerDelay() {
        long startedAt = System.currentTimeMillis();

        scenario.execute("exp-1", Map.of("messageCount", 1, "consumerDelayMs", 60_000));

        long elapsedMs = System.currentTimeMillis() - startedAt;
        assertThat(elapsedMs).isLessThan(500L);
        verify(mqBacklogMessageService).publishOrders("exp-1", 1, 60_000L);
    }
}
