package com.faultlab.backend.rule.diagnoser;

import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class MqBacklogRuleDiagnoser implements RuleDiagnoser {

    private static final String FAULT_NAME = "MQ 消息堆积";
    private static final List<String> DEFAULT_SUGGESTIONS = List.of(
            "增加消费者并发",
            "排查消费端慢逻辑或慢 SQL",
            "检查 RabbitMQ 队列积压情况",
            "引入死信队列和消费端幂等",
            "对长耗时消费逻辑做异步拆分或限流保护"
    );

    @Override
    public boolean supports(String scenarioCode) {
        return ScenarioCode.MQ_BACKLOG.equals(scenarioCode);
    }

    @Override
    public RuleDiagnosisResult diagnose(FaultExperiment experiment, List<FaultMetric> metrics) {
        Map<String, BigDecimal> metricValues = metrics == null ? Map.of() : metrics.stream()
                .filter(metric -> metric.getMetricName() != null && metric.getMetricValue() != null)
                .collect(Collectors.toMap(
                        FaultMetric::getMetricName,
                        FaultMetric::getMetricValue,
                        (left, right) -> right
                ));

        Optional<BigDecimal> publishCount = metric(metricValues, "publishCount");
        Optional<BigDecimal> consumeCount = metric(metricValues, "consumeCount");
        Optional<BigDecimal> consumerDelayMs = metric(metricValues, "consumerDelayMs");
        Optional<BigDecimal> avgConsumeMs = metric(metricValues, "avgConsumeMs");

        RuleDiagnosisResult result = baseResult(experiment);
        List<String> evidence = buildEvidence(publishCount, consumeCount, consumerDelayMs, avgConsumeMs);

        if (publishCount.isEmpty() || consumeCount.isEmpty()
                || (consumerDelayMs.isEmpty() && avgConsumeMs.isEmpty())) {
            result.setMatched(false);
            result.setConfidence(0.20);
            result.setReason("关键指标不足，无法确认是否存在 MQ 消息堆积。");
            result.setEvidence(evidence);
            return result;
        }

        long backlogCount = publishCount.get().longValue() - consumeCount.get().longValue();
        boolean backlogMatched = backlogCount > 0;
        boolean consumeSlow = avgConsumeMs.map(value -> value.compareTo(BigDecimal.valueOf(500)) >= 0).orElse(false)
                || consumerDelayMs.map(value -> value.compareTo(BigDecimal.valueOf(500)) >= 0).orElse(false);
        evidence.add("backlogCount=" + backlogCount);

        if (backlogMatched && consumeSlow) {
            result.setMatched(true);
            result.setConfidence(0.85);
            result.setReason("生产消息数大于消费消息数，且消费耗时较高，疑似 MQ 消息堆积。");
        } else if (backlogMatched) {
            result.setMatched(true);
            result.setConfidence(0.65);
            result.setReason("生产消息数大于消费消息数，存在消息积压，但消费耗时未达到慢消费阈值。");
        } else if (consumeSlow) {
            result.setMatched(true);
            result.setConfidence(0.55);
            result.setReason("消费耗时较高，但暂未观察到生产数大于消费数的积压证据。");
        } else {
            result.setMatched(false);
            result.setConfidence(0.20);
            result.setReason("生产消费数量基本匹配，且消费耗时未达到慢消费阈值，暂未命中 MQ 消息堆积规则。");
        }

        result.setEvidence(evidence);
        return result;
    }

    private RuleDiagnosisResult baseResult(FaultExperiment experiment) {
        RuleDiagnosisResult result = new RuleDiagnosisResult();
        result.setExperimentId(experiment == null ? null : experiment.getExperimentId());
        result.setFaultType(ScenarioCode.MQ_BACKLOG);
        result.setFaultName(FAULT_NAME);
        result.setSuggestions(DEFAULT_SUGGESTIONS);
        result.setEvidence(List.of());
        return result;
    }

    private List<String> buildEvidence(
            Optional<BigDecimal> publishCount,
            Optional<BigDecimal> consumeCount,
            Optional<BigDecimal> consumerDelayMs,
            Optional<BigDecimal> avgConsumeMs
    ) {
        List<String> evidence = new ArrayList<>();
        publishCount.ifPresent(value -> evidence.add("publishCount=" + value.longValue()));
        consumeCount.ifPresent(value -> evidence.add("consumeCount=" + value.longValue()));
        avgConsumeMs.ifPresent(value -> evidence.add("avgConsumeMs=" + value.longValue()));
        consumerDelayMs.ifPresent(value -> evidence.add("consumerDelayMs=" + value.longValue()));
        return evidence;
    }

    private Optional<BigDecimal> metric(Map<String, BigDecimal> metricValues, String metricName) {
        return Optional.ofNullable(metricValues.get(metricName));
    }
}
