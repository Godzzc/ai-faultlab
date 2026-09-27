package com.faultlab.backend.experiment.remediation;

import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.common.ErrorCode;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemediationReplayPolicyTests {

    private final RemediationReplayPolicy policy = new RemediationReplayPolicy();

    @Test
    void shouldAllowCacheBreakdownMutexPatch() {
        Map<String, Object> patch = policy.validateAndNormalize(
                ScenarioCode.CACHE_BREAKDOWN,
                Map.of("enableMutex", true)
        );

        assertThat(patch).containsEntry("enableMutex", true);
    }

    @Test
    void shouldAllowDbConnectionPoolFastReleasePatch() {
        Map<String, Object> patch = policy.validateAndNormalize(
                ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION,
                Map.of("enableFastRelease", true)
        );

        assertThat(patch).containsEntry("enableFastRelease", true);
    }

    @Test
    void shouldAllowDownstreamFallbackPatch() {
        Map<String, Object> patch = policy.validateAndNormalize(
                ScenarioCode.DOWNSTREAM_TIMEOUT,
                Map.of("enableFallback", true)
        );

        assertThat(patch).containsEntry("enableFallback", true);
    }

    @Test
    void shouldAllowRetryStormReplayPatch() {
        Map<String, Object> patch = policy.validateAndNormalize(
                ScenarioCode.RETRY_STORM,
                Map.of("enableRetryLimit", true, "enableJitter", true, "retryBackoffMs", 100L)
        );

        assertThat(patch)
                .containsEntry("enableRetryLimit", true)
                .containsEntry("enableJitter", true)
                .containsEntry("retryBackoffMs", 100);
    }

    @Test
    void shouldRejectUnknownParameter() {
        assertPolicyError(
                () -> policy.validateAndNormalize(ScenarioCode.CACHE_BREAKDOWN, Map.of("unknown", true)),
                ErrorCode.REMEDIATION_PARAMETER_NOT_ALLOWED
        );
    }

    @Test
    void shouldRejectScenarioCodePatch() {
        assertPolicyError(
                () -> policy.validateAndNormalize(ScenarioCode.RETRY_STORM, Map.of("scenarioCode", ScenarioCode.CACHE_BREAKDOWN)),
                ErrorCode.REMEDIATION_PARAMETER_NOT_ALLOWED
        );
    }

    @Test
    void shouldRejectExperimentIdPatch() {
        assertPolicyError(
                () -> policy.validateAndNormalize(ScenarioCode.RETRY_STORM, Map.of("experimentId", "exp_other")),
                ErrorCode.REMEDIATION_PARAMETER_NOT_ALLOWED
        );
    }

    @Test
    void shouldRejectFaultConditionParameterPatch() {
        assertPolicyError(
                () -> policy.validateAndNormalize(ScenarioCode.RETRY_STORM, Map.of("failureRatio", 0.1D)),
                ErrorCode.REMEDIATION_PARAMETER_NOT_ALLOWED
        );
    }

    @Test
    void shouldRejectWrongParameterType() {
        assertPolicyError(
                () -> policy.validateAndNormalize(ScenarioCode.DOWNSTREAM_TIMEOUT, Map.of("enableFallback", "true")),
                ErrorCode.INVALID_REMEDIATION_PATCH
        );
    }

    @Test
    void shouldRejectInvalidRetryBackoffMs() {
        assertPolicyError(
                () -> policy.validateAndNormalize(ScenarioCode.RETRY_STORM, Map.of("retryBackoffMs", 0)),
                ErrorCode.INVALID_REMEDIATION_PATCH
        );
    }

    @Test
    void shouldRejectUnsupportedScenario() {
        assertPolicyError(
                () -> policy.validateAndNormalize(ScenarioCode.MQ_BACKLOG, Map.of("enableRetryLimit", true)),
                ErrorCode.REMEDIATION_REPLAY_UNSUPPORTED_SCENARIO
        );
    }

    private void assertPolicyError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }
}
