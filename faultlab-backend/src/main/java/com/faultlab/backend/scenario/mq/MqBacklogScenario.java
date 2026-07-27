package com.faultlab.backend.scenario.mq;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MqBacklogScenario implements FaultScenario {

    public static final String SCENARIO_NAME = "MQ 消息堆积";
    public static final int DEFAULT_MESSAGE_COUNT = 100;
    public static final long DEFAULT_CONSUMER_DELAY_MS = 1000L;

    private final MqBacklogMessageService mqBacklogMessageService;
    private final MetricService metricService;

    public MqBacklogScenario(MqBacklogMessageService mqBacklogMessageService, MetricService metricService) {
        this.mqBacklogMessageService = mqBacklogMessageService;
        this.metricService = metricService;
    }

    @Override
    public String scenarioCode() {
        return ScenarioCode.MQ_BACKLOG;
    }

    @Override
    public String scenarioName() {
        return SCENARIO_NAME;
    }

    @Override
    public void execute(String experimentId, Map<String, Object> params) {
        int messageCount = readInt(params, "messageCount", DEFAULT_MESSAGE_COUNT);
        long consumerDelayMs = readLong(params, "consumerDelayMs", DEFAULT_CONSUMER_DELAY_MS);

        metricService.recordMetric(experimentId, "messageCount", messageCount, "count", "RabbitMQ");
        metricService.recordMetric(experimentId, "consumerDelayMs", consumerDelayMs, "ms", "RabbitMQ");

        mqBacklogMessageService.publishOrders(experimentId, messageCount, consumerDelayMs);
    }

    private int readInt(Map<String, Object> params, String key, int defaultValue) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return defaultValue;
    }

    private long readLong(Map<String, Object> params, String key, long defaultValue) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return defaultValue;
    }
}
