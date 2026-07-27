package com.faultlab.backend.rule;

import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.rule.diagnoser.MqBacklogRuleDiagnoser;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MqBacklogRuleDiagnoserTests {

    private final MqBacklogRuleDiagnoser diagnoser = new MqBacklogRuleDiagnoser();

    @Test
    void shouldDiagnoseMqBacklogWhenPublishGreaterThanConsumeAndConsumeSlow() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(), List.of(
                metric("publishCount", 100),
                metric("consumeCount", 20),
                metric("consumerDelayMs", 1000),
                metric("avgConsumeMs", 1000)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getConfidence()).isEqualTo(0.85);
        assertThat(result.getEvidence()).contains("publishCount=100", "consumeCount=20", "backlogCount=80", "avgConsumeMs=1000");
        assertThat(result.getSuggestions()).contains("增加消费者并发", "检查 RabbitMQ 队列积压情况");
    }

    @Test
    void shouldReturnLowerConfidenceWhenOnlyBacklogMatched() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(), List.of(
                metric("publishCount", 100),
                metric("consumeCount", 20),
                metric("consumerDelayMs", 100),
                metric("avgConsumeMs", 100)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getConfidence()).isEqualTo(0.65);
        assertThat(result.getReason()).contains("存在消息积压");
    }

    @Test
    void shouldReturnLowerConfidenceWhenOnlyConsumeSlowMatched() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(), List.of(
                metric("publishCount", 100),
                metric("consumeCount", 100),
                metric("consumerDelayMs", 1000),
                metric("avgConsumeMs", 1000)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getConfidence()).isEqualTo(0.55);
        assertThat(result.getReason()).contains("消费耗时较高");
    }

    @Test
    void shouldReturnNotMatchedWhenMetricsMissing() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(), List.of(metric("publishCount", 100)));

        assertThat(result.getMatched()).isFalse();
        assertThat(result.getConfidence()).isEqualTo(0.20);
        assertThat(result.getReason()).contains("关键指标不足");
    }

    private FaultExperiment experiment() {
        FaultExperiment experiment = new FaultExperiment();
        experiment.setExperimentId("exp-1");
        experiment.setScenarioCode(ScenarioCode.MQ_BACKLOG);
        return experiment;
    }

    private FaultMetric metric(String metricName, long metricValue) {
        FaultMetric metric = new FaultMetric();
        metric.setExperimentId("exp-1");
        metric.setMetricName(metricName);
        metric.setMetricValue(BigDecimal.valueOf(metricValue));
        return metric;
    }
}
