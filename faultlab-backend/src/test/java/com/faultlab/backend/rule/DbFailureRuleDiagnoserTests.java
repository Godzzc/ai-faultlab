package com.faultlab.backend.rule;

import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.rule.diagnoser.DbFailureRuleDiagnoser;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DbFailureRuleDiagnoserTests {

    private final DbFailureRuleDiagnoser diagnoser = new DbFailureRuleDiagnoser();

    @Test
    void shouldSupportDbScenarioCodes() {
        assertThat(diagnoser.supports(ScenarioCode.DB_SLOW_QUERY)).isTrue();
        assertThat(diagnoser.supports(ScenarioCode.DB_LOCK_CONTENTION)).isTrue();
        assertThat(diagnoser.supports(ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION)).isTrue();
        assertThat(diagnoser.supports(ScenarioCode.CACHE_PENETRATION)).isFalse();
    }

    @Test
    void shouldDiagnoseSlowQuery() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(ScenarioCode.DB_SLOW_QUERY), List.of(
                metric("db.query.count", 100),
                metric("db.slow.query.count", 100),
                metric("db.slow.query.rate", 1),
                metric("db.avg.query.ms", 80),
                metric("db.max.query.ms", 96),
                metric("db.full.scan.count", 100),
                metric("db.scanned.rows", 80000),
                metric("db.table.size", 100000)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getFaultType()).isEqualTo(ScenarioCode.DB_SLOW_QUERY);
        assertThat(result.getEvidence()).contains("slowQueryCount=100", "fullScanCount=100");
    }

    @Test
    void shouldDiagnoseLockContention() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(ScenarioCode.DB_LOCK_CONTENTION), List.of(
                metric("db.lock.request.count", 50),
                metric("db.lock.wait.count", 49),
                metric("db.avg.lock.wait.ms", 100),
                metric("db.max.lock.wait.ms", 100),
                metric("db.update.timeout.count", 49),
                metric("db.long.transaction.count", 5),
                metric("db.transaction.active.count", 10)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getFaultType()).isEqualTo(ScenarioCode.DB_LOCK_CONTENTION);
        assertThat(result.getEvidence()).contains("lockWaitCount=49", "updateTimeoutCount=49");
    }

    @Test
    void shouldDiagnoseConnectionPoolExhaustion() {
        RuleDiagnosisResult result = diagnoser.diagnose(experiment(ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION), List.of(
                metric("db.connection.acquire.count", 100),
                metric("db.pool.max.size", 10),
                metric("db.pool.active.count", 10),
                metric("db.connection.acquire.timeout.count", 90),
                metric("db.connection.acquire.avg.ms", 50),
                metric("db.connection.hold.avg.ms", 200),
                metric("api.error.count", 90)
        ));

        assertThat(result.getMatched()).isTrue();
        assertThat(result.getFaultType()).isEqualTo(ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION);
        assertThat(result.getEvidence()).contains("acquireTimeoutCount=90", "apiErrorCount=90");
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
