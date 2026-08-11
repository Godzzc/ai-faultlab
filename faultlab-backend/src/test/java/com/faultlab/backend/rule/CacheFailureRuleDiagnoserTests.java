package com.faultlab.backend.rule;

import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.rule.diagnoser.CacheFailureRuleDiagnoser;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheFailureRuleDiagnoserTests {

    private final CacheFailureRuleDiagnoser diagnoser = new CacheFailureRuleDiagnoser();

    @Test
    void shouldSupportCacheScenarioCodes() {
        assertThat(diagnoser.supports(ScenarioCode.CACHE_PENETRATION)).isTrue();
        assertThat(diagnoser.supports(ScenarioCode.CACHE_BREAKDOWN)).isTrue();
        assertThat(diagnoser.supports(ScenarioCode.CACHE_AVALANCHE)).isTrue();
        assertThat(diagnoser.supports(ScenarioCode.MQ_BACKLOG)).isFalse();
    }

    @Test
    void shouldDiagnoseCachePenetration() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(ScenarioCode.CACHE_PENETRATION), List.of(
                metric("cache.request.count", 100),
                metric("cache.miss.rate", 0.8),
                metric("cache.db.query.count", 90),
                metric("cache.invalid.key.count", 80),
                metric("cache.bloom.reject.count", 0),
                metric("cache.null.cache.write.count", 0)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getFaultType()).isEqualTo(ScenarioCode.CACHE_PENETRATION);
        assertThat(result.getEvidence()).contains("dbQueryCount=90", "invalidKeyCount=80");
    }

    @Test
    void shouldDiagnoseCacheBreakdown() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(ScenarioCode.CACHE_BREAKDOWN), List.of(
                metric("cache.hot.key.request.count", 100),
                metric("cache.hot.key.miss.count", 100),
                metric("cache.db.query.count", 100),
                metric("cache.rebuild.count", 100),
                metric("cache.lock.acquire.count", 0),
                metric("cache.lock.fail.count", 0),
                metric("cache.concurrent.rebuild.count", 20)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getFaultType()).isEqualTo(ScenarioCode.CACHE_BREAKDOWN);
        assertThat(result.getEvidence()).contains("rebuildCount=100");
    }

    @Test
    void shouldDiagnoseCacheAvalanche() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(ScenarioCode.CACHE_AVALANCHE), List.of(
                metric("cache.key.count", 50),
                metric("cache.expired.key.count", 50),
                metric("cache.miss.rate", 1),
                metric("cache.db.query.count", 200),
                metric("cache.unavailable.count", 0),
                metric("cache.fallback.count", 0),
                metric("cache.request.error.count", 0)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getFaultType()).isEqualTo(ScenarioCode.CACHE_AVALANCHE);
        assertThat(result.getEvidence()).contains("expiredKeyCount=50");
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
