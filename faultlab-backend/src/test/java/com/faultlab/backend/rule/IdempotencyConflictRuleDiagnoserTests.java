package com.faultlab.backend.rule;

import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.rule.diagnoser.IdempotencyConflictRuleDiagnoser;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyConflictRuleDiagnoserTests {

    private final IdempotencyConflictRuleDiagnoser diagnoser = new IdempotencyConflictRuleDiagnoser();

    @Test
    void shouldDiagnoseIdempotencyConflictWhenHashMismatchExists() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(), List.of(
                metric("requestCount", 30),
                metric("duplicateCount", 20),
                metric("conflictCount", 8),
                metric("acceptedCount", 1),
                metric("rejectedDuplicateCount", 20),
                metric("hashMismatchCount", 8),
                metric("redisSetNxSuccessCount", 1),
                metric("redisSetNxFailCount", 28),
                metric("redisErrorCount", 0),
                metric("avgCheckDurationMs", 10)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getConfidence()).isEqualTo(0.90);
        assertThat(result.getFaultType()).isEqualTo(ScenarioCode.IDEMPOTENCY_CONFLICT);
        assertThat(result.getEvidence()).contains("requestCount=30", "duplicateCount=20", "hashMismatchCount=8");
        assertThat(result.getSuggestions()).contains("服务端保存 requestHash，避免同一幂等 key 携带不同请求体");
    }

    @Test
    void shouldDiagnoseDuplicateSubmitWhenDuplicateCountExists() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(), List.of(
                metric("requestCount", 20),
                metric("duplicateCount", 10),
                metric("conflictCount", 0),
                metric("acceptedCount", 1),
                metric("rejectedDuplicateCount", 10),
                metric("hashMismatchCount", 0),
                metric("redisSetNxSuccessCount", 1),
                metric("redisSetNxFailCount", 10),
                metric("redisErrorCount", 0)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getConfidence()).isEqualTo(0.65);
        assertThat(result.getReason()).contains("重复提交");
    }

    @Test
    void shouldReturnLowerConfidenceWhenOnlyRedisErrorExists() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(), List.of(
                metric("requestCount", 5),
                metric("duplicateCount", 0),
                metric("conflictCount", 0),
                metric("acceptedCount", 0),
                metric("rejectedDuplicateCount", 0),
                metric("hashMismatchCount", 0),
                metric("redisSetNxSuccessCount", 0),
                metric("redisSetNxFailCount", 0),
                metric("redisErrorCount", 5)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getConfidence()).isEqualTo(0.55);
        assertThat(result.getReason()).contains("Redis 幂等检查出现异常");
    }

    @Test
    void shouldReturnNotMatchedWhenMetricsMissing() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(), List.of(metric("requestCount", 30)));

        assertThat(result.getMatched()).isFalse();
        assertThat(result.getConfidence()).isEqualTo(0.20);
        assertThat(result.getReason()).contains("关键幂等指标不足");
    }

    private FaultExperiment experiment() {
        FaultExperiment experiment = new FaultExperiment();
        experiment.setExperimentId("exp-1");
        experiment.setScenarioCode(ScenarioCode.IDEMPOTENCY_CONFLICT);
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
