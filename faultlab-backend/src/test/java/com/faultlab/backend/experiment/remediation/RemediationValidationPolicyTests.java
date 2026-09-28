package com.faultlab.backend.experiment.remediation;

import com.faultlab.backend.experiment.dto.ExpectedMetricDirection;
import com.faultlab.backend.experiment.dto.ExpectedMetricEffect;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemediationValidationPolicyTests {

    private final RemediationValidationPolicy policy = new RemediationValidationPolicy();

    @Test
    void cacheBreakdownMutexShouldUseRealMetricNames() {
        List<ExpectedMetricEffect> effects = policy.resolveExpectedEffects(
                ScenarioCode.CACHE_BREAKDOWN,
                Map.of("enableMutex", false),
                Map.of("enableMutex", true),
                Map.of("enableMutex", true)
        );

        assertThat(effects).extracting(ExpectedMetricEffect::getMetricName)
                .containsExactly(
                        "cache.concurrent.rebuild.count",
                        "cache.rebuild.count",
                        "cache.db.query.count"
                );
    }

    @Test
    void dbConnectionPoolShouldUseRealMetricNames() {
        List<ExpectedMetricEffect> effects = policy.resolveExpectedEffects(
                ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION,
                Map.of("enableFastRelease", false),
                Map.of("enableFastRelease", true),
                Map.of("enableFastRelease", true)
        );

        assertThat(effects).extracting(ExpectedMetricEffect::getMetricName)
                .contains(
                        "db.connection.acquire.timeout.count",
                        "db.connection.acquire.avg.ms",
                        "db.connection.hold.avg.ms",
                        "api.error.count"
                );
    }

    @Test
    void downstreamFallbackShouldKeepTimeoutStable() {
        List<ExpectedMetricEffect> effects = policy.resolveExpectedEffects(
                ScenarioCode.DOWNSTREAM_TIMEOUT,
                Map.of("enableFallback", false),
                Map.of("enableFallback", true),
                Map.of("enableFallback", true)
        );

        assertThat(effects)
                .filteredOn(effect -> "downstream.timeout.count".equals(effect.getMetricName()))
                .extracting(ExpectedMetricEffect::getDirection)
                .containsExactly(ExpectedMetricDirection.STABLE);
        assertThat(effects).extracting(ExpectedMetricEffect::getMetricName)
                .contains("api.error.count", "downstream.fallback.count");
    }

    @Test
    void retryStormRetryLimitShouldExpectAmplificationDecreaseAndExhaustedStable() {
        List<ExpectedMetricEffect> effects = policy.resolveExpectedEffects(
                ScenarioCode.RETRY_STORM,
                Map.of("enableRetryLimit", false),
                Map.of("enableRetryLimit", true),
                Map.of("enableRetryLimit", true)
        );

        assertThat(effects)
                .filteredOn(effect -> "downstream.retry.amplification.factor".equals(effect.getMetricName()))
                .extracting(ExpectedMetricEffect::getDirection)
                .containsExactly(ExpectedMetricDirection.DECREASE);
        assertThat(effects)
                .filteredOn(effect -> "downstream.retry.exhausted.count".equals(effect.getMetricName()))
                .extracting(ExpectedMetricEffect::getDirection)
                .containsExactly(ExpectedMetricDirection.STABLE);
    }
}
