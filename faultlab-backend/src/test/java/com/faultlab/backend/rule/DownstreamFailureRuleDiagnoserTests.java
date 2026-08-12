package com.faultlab.backend.rule;

import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.rule.diagnoser.DownstreamFailureRuleDiagnoser;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamFailureRuleDiagnoserTests {

    private final DownstreamFailureRuleDiagnoser diagnoser = new DownstreamFailureRuleDiagnoser();

    @Test
    void shouldSupportDownstreamScenarioCodes() {
        assertThat(diagnoser.supports(ScenarioCode.DOWNSTREAM_TIMEOUT)).isTrue();
        assertThat(diagnoser.supports(ScenarioCode.RETRY_STORM)).isTrue();
        assertThat(diagnoser.supports(ScenarioCode.CIRCUIT_BREAKER_OPEN)).isTrue();
        assertThat(diagnoser.supports(ScenarioCode.DB_SLOW_QUERY)).isFalse();
    }

    @Test
    void shouldDiagnoseDownstreamTimeout() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(ScenarioCode.DOWNSTREAM_TIMEOUT), List.of(
                metric("downstream.request.count", 100),
                metric("downstream.timeout.count", 80),
                metric("downstream.timeout.rate", 0.8),
                metric("downstream.avg.latency.ms", 240),
                metric("downstream.max.latency.ms", 300),
                metric("downstream.fallback.count", 0),
                metric("api.error.count", 80)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getFaultType()).isEqualTo(ScenarioCode.DOWNSTREAM_TIMEOUT);
        assertThat(result.getEvidence()).contains("timeoutCount=80", "apiErrorCount=80");
    }

    @Test
    void shouldDiagnoseRetryStorm() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(ScenarioCode.RETRY_STORM), List.of(
                metric("downstream.initial.request.count", 100),
                metric("downstream.total.call.count", 310),
                metric("downstream.retry.count", 210),
                metric("downstream.retry.rate", 2.1),
                metric("downstream.retry.exhausted.count", 70),
                metric("downstream.retry.amplification.factor", 3.1),
                metric("api.error.count", 70)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getFaultType()).isEqualTo(ScenarioCode.RETRY_STORM);
        assertThat(result.getEvidence()).contains("retryCount=210", "amplificationFactor=3.1");
    }

    @Test
    void shouldDiagnoseCircuitBreakerOpen() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(ScenarioCode.CIRCUIT_BREAKER_OPEN), List.of(
                metric("circuit.request.count", 100),
                metric("circuit.failure.count", 16),
                metric("circuit.failure.rate", 0.8),
                metric("circuit.slow.call.count", 10),
                metric("circuit.slow.call.rate", 0.5),
                metric("circuit.open.count", 1),
                metric("circuit.rejected.count", 80),
                metric("circuit.fallback.count", 80),
                metric("downstream.call.skipped.count", 80)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getFaultType()).isEqualTo(ScenarioCode.CIRCUIT_BREAKER_OPEN);
        assertThat(result.getEvidence()).contains("openCount=1", "skippedDownstreamCallCount=80");
    }

    private FaultExperiment experiment(String scenarioCode) {
        FaultExperiment experiment = new FaultExperiment();
        experiment.setExperimentId("exp-1");
        experiment.setScenarioCode(scenarioCode);
        return experiment;
    }

    private FaultMetric metric(String name, double value) {
        FaultMetric metric = new FaultMetric();
        metric.setMetricName(name);
        metric.setMetricValue(BigDecimal.valueOf(value));
        return metric;
    }
}
