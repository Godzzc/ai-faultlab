package com.faultlab.backend.rule;

import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.rule.diagnoser.ThreadPoolSaturationRuleDiagnoser;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadPoolSaturationRuleDiagnoserTests {

    private final ThreadPoolSaturationRuleDiagnoser diagnoser = new ThreadPoolSaturationRuleDiagnoser();

    @Test
    void shouldDiagnoseThreadPoolSaturationWhenRejectedAndQueued() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(), List.of(
                metric("taskCount", 30),
                metric("acceptedTaskCount", 12),
                metric("rejectedTaskCount", 18),
                metric("activeThreadCount", 2),
                metric("queueSize", 10),
                metric("avgTaskDurationMs", 3000)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getConfidence()).isEqualTo(0.90);
        assertThat(result.getFaultType()).isEqualTo(ScenarioCode.THREAD_POOL_SATURATION);
        assertThat(result.getEvidence()).contains("taskCount=30", "rejectedTaskCount=18", "queueSize=10");
        assertThat(result.getSuggestions()).contains("合理设置任务队列容量", "增加拒绝策略监控和告警");
    }

    @Test
    void shouldDiagnoseThreadPoolSaturationWhenQueueBacklogAndActiveThreadsFull() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(), List.of(
                metric("taskCount", 12),
                metric("acceptedTaskCount", 12),
                metric("rejectedTaskCount", 0),
                metric("activeThreadCount", 2),
                metric("queueSize", 8),
                metric("avgTaskDurationMs", 1500)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getConfidence()).isEqualTo(0.80);
        assertThat(result.getReason()).contains("工作线程已被占满");
    }

    @Test
    void shouldReturnLowerConfidenceWhenOnlyLongTask() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(), List.of(
                metric("taskCount", 3),
                metric("acceptedTaskCount", 3),
                metric("rejectedTaskCount", 0),
                metric("activeThreadCount", 1),
                metric("queueSize", 0),
                metric("taskSleepMs", 3000)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getConfidence()).isEqualTo(0.60);
        assertThat(result.getReason()).contains("长任务阻塞");
    }

    @Test
    void shouldReturnNotMatchedWhenMetricsMissing() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(), List.of(metric("taskCount", 30)));

        assertThat(result.getMatched()).isFalse();
        assertThat(result.getConfidence()).isEqualTo(0.20);
        assertThat(result.getReason()).contains("关键线程池指标不足");
    }

    private FaultExperiment experiment() {
        FaultExperiment experiment = new FaultExperiment();
        experiment.setExperimentId("exp-1");
        experiment.setScenarioCode(ScenarioCode.THREAD_POOL_SATURATION);
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
