package com.faultlab.backend.experiment.remediation;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.common.ErrorCode;
import com.faultlab.backend.experiment.dto.MetricComparisonStatus;
import com.faultlab.backend.experiment.dto.RemediationValidationReport;
import com.faultlab.backend.experiment.dto.RemediationValidationStatus;
import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.experiment.mapper.FaultExperimentMapper;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.metric.mapper.FaultMetricMapper;
import com.faultlab.backend.scenario.model.ExperimentStatus;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.entity.TraceSpan;
import com.faultlab.backend.trace.manager.TraceManager;
import com.faultlab.backend.trace.mapper.TraceSpanMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemediationValidationServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FaultExperimentMapper faultExperimentMapper = mock(FaultExperimentMapper.class);
    private final FaultMetricMapper faultMetricMapper = mock(FaultMetricMapper.class);
    private final TraceSpanMapper traceSpanMapper = mock(TraceSpanMapper.class);
    private final RemediationValidationService service = new RemediationValidationService(
            faultExperimentMapper,
            faultMetricMapper,
            traceSpanMapper,
            objectMapper,
            new RemediationReplayPolicy(),
            new RemediationValidationPolicy(),
            new RemediationMetricComparator()
    );

    @Test
    void shouldRejectWhenReplayExperimentDoesNotExist() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertServiceError(
                () -> service.validate("exp_replay"),
                ErrorCode.REMEDIATION_VALIDATION_REPLAY_NOT_FOUND
        );
    }

    @Test
    void shouldRejectWhenReplaySourceExperimentIdIsMissing() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(
                "exp_replay",
                ScenarioCode.RETRY_STORM,
                json(Map.of("enableRetryLimit", true)),
                null,
                "trace_replay"
        ));

        assertServiceError(
                () -> service.validate("exp_replay"),
                ErrorCode.REMEDIATION_VALIDATION_SOURCE_MISSING
        );
    }

    @Test
    void shouldRejectWhenOriginalExperimentDoesNotExist() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class)))
                .thenReturn(experiment(
                        "exp_replay",
                        ScenarioCode.RETRY_STORM,
                        json(Map.of("enableRetryLimit", true)),
                        "exp_original",
                        "trace_replay"
                ))
                .thenReturn(null);

        assertServiceError(
                () -> service.validate("exp_replay"),
                ErrorCode.REMEDIATION_VALIDATION_ORIGINAL_NOT_FOUND
        );
    }

    @Test
    void shouldRejectScenarioMismatch() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class)))
                .thenReturn(experiment("exp_replay", ScenarioCode.RETRY_STORM, json(Map.of()), "exp_original", "trace_replay"))
                .thenReturn(experiment("exp_original", ScenarioCode.DOWNSTREAM_TIMEOUT, json(Map.of()), null, "trace_original"));

        assertServiceError(
                () -> service.validate("exp_replay"),
                ErrorCode.SCENARIO_MISMATCH
        );
    }

    @Test
    void shouldRejectOriginalParamsUnavailable() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class)))
                .thenReturn(experiment("exp_replay", ScenarioCode.RETRY_STORM, json(Map.of()), "exp_original", "trace_replay"))
                .thenReturn(experiment("exp_original", ScenarioCode.RETRY_STORM, null, null, "trace_original"));

        assertServiceError(
                () -> service.validate("exp_replay"),
                ErrorCode.REMEDIATION_VALIDATION_PARAMS_UNAVAILABLE
        );
    }

    @Test
    void shouldRejectReplayParamsUnavailable() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class)))
                .thenReturn(experiment("exp_replay", ScenarioCode.RETRY_STORM, null, "exp_original", "trace_replay"))
                .thenReturn(experiment("exp_original", ScenarioCode.RETRY_STORM, json(Map.of()), null, "trace_original"));

        assertServiceError(
                () -> service.validate("exp_replay"),
                ErrorCode.REMEDIATION_VALIDATION_PARAMS_UNAVAILABLE
        );
    }

    @Test
    void downstreamTimeoutAllEffectsMatchShouldBeVerified() {
        givenExperiments(
                ScenarioCode.DOWNSTREAM_TIMEOUT,
                Map.of("requestCount", 100, "enableFallback", false),
                Map.of("requestCount", 100, "enableFallback", true)
        );
        givenMetrics(
                List.of(
                        metric("api.error.count", 80),
                        metric("downstream.fallback.count", 0),
                        metric("downstream.timeout.count", 80)
                ),
                List.of(
                        metric("api.error.count", 0),
                        metric("downstream.fallback.count", 80),
                        metric("downstream.timeout.count", 80)
                )
        );
        givenTrace(List.of(span("trace_original", "downstream.call", TraceManager.STATUS_SUCCESS, 10)),
                List.of(span("trace_replay", "fallback.execute", TraceManager.STATUS_SUCCESS, 5)));

        RemediationValidationReport report = service.validate("exp_replay");

        assertThat(report.getStatus()).isEqualTo(RemediationValidationStatus.VERIFIED);
        assertThat(report.getMatchedEffects()).isEqualTo(3);
        assertThat(report.getAppliedPatch()).containsEntry("enableFallback", true);
        assertThat(report.getSummary()).isEqualTo("3 of 3 expected metric effects matched.");
    }

    @Test
    void someMatchedEffectsShouldBePartiallyVerified() {
        givenExperiments(
                ScenarioCode.DOWNSTREAM_TIMEOUT,
                Map.of("requestCount", 100, "enableFallback", false),
                Map.of("requestCount", 100, "enableFallback", true)
        );
        givenMetrics(
                List.of(
                        metric("api.error.count", 80),
                        metric("downstream.fallback.count", 0),
                        metric("downstream.timeout.count", 80)
                ),
                List.of(
                        metric("api.error.count", 0),
                        metric("downstream.fallback.count", 0),
                        metric("downstream.timeout.count", 80)
                )
        );
        givenTrace(List.of(), List.of());

        RemediationValidationReport report = service.validate("exp_replay");

        assertThat(report.getStatus()).isEqualTo(RemediationValidationStatus.PARTIALLY_VERIFIED);
        assertThat(report.getMatchedEffects()).isEqualTo(2);
        assertThat(report.getUnmatchedEffects()).isEqualTo(1);
    }

    @Test
    void allFailedEffectsShouldBeNotVerified() {
        givenExperiments(
                ScenarioCode.DOWNSTREAM_TIMEOUT,
                Map.of("requestCount", 100, "enableFallback", false),
                Map.of("requestCount", 100, "enableFallback", true)
        );
        givenMetrics(
                List.of(
                        metric("api.error.count", 80),
                        metric("downstream.fallback.count", 10),
                        metric("downstream.timeout.count", 80)
                ),
                List.of(
                        metric("api.error.count", 90),
                        metric("downstream.fallback.count", 5),
                        metric("downstream.timeout.count", 70)
                )
        );
        givenTrace(List.of(), List.of());

        RemediationValidationReport report = service.validate("exp_replay");

        assertThat(report.getStatus()).isEqualTo(RemediationValidationStatus.NOT_VERIFIED);
        assertThat(report.getMatchedEffects()).isZero();
        assertThat(report.getUnmatchedEffects()).isEqualTo(3);
    }

    @Test
    void missingExpectedMetricShouldMakeReportInconclusive() {
        givenExperiments(
                ScenarioCode.DOWNSTREAM_TIMEOUT,
                Map.of("requestCount", 100, "enableFallback", false),
                Map.of("requestCount", 100, "enableFallback", true)
        );
        givenMetrics(
                List.of(metric("api.error.count", 80), metric("downstream.timeout.count", 80)),
                List.of(metric("api.error.count", 0), metric("downstream.timeout.count", 80))
        );
        givenTrace(List.of(), List.of());

        RemediationValidationReport report = service.validate("exp_replay");

        assertThat(report.getStatus()).isEqualTo(RemediationValidationStatus.INCONCLUSIVE);
        assertThat(report.getInconclusiveEffects()).isEqualTo(1);
        assertThat(report.getMetricComparisons())
                .filteredOn(comparison -> comparison.getStatus() == MetricComparisonStatus.INCONCLUSIVE)
                .extracting("metricName")
                .containsExactly("downstream.fallback.count");
    }

    @Test
    void emptyMetricsShouldMakeReportInconclusive() {
        givenExperiments(
                ScenarioCode.RETRY_STORM,
                Map.of("requestCount", 100, "failureRatio", 0.7D, "enableRetryLimit", false),
                Map.of("requestCount", 100, "failureRatio", 0.7D, "enableRetryLimit", true)
        );
        givenMetrics(List.of(), List.of());
        givenTrace(List.of(), List.of());

        RemediationValidationReport report = service.validate("exp_replay");

        assertThat(report.getStatus()).isEqualTo(RemediationValidationStatus.INCONCLUSIVE);
        assertThat(report.getInconclusiveEffects()).isEqualTo(report.getTotalExpectedEffects());
    }

    @Test
    void retryStormAmplificationDecreaseShouldMatch() {
        givenExperiments(
                ScenarioCode.RETRY_STORM,
                Map.of("requestCount", 100, "failureRatio", 0.7D, "maxRetries", 3, "enableRetryLimit", false),
                Map.of("requestCount", 100, "failureRatio", 0.7D, "maxRetries", 3, "enableRetryLimit", true)
        );
        givenMetrics(
                List.of(
                        metric("downstream.retry.count", 210),
                        metric("downstream.retry.amplification.factor", 3.1D),
                        metric("downstream.total.call.count", 310),
                        metric("downstream.retry.exhausted.count", 70)
                ),
                List.of(
                        metric("downstream.retry.count", 70),
                        metric("downstream.retry.amplification.factor", 1.7D),
                        metric("downstream.total.call.count", 170),
                        metric("downstream.retry.exhausted.count", 70)
                )
        );
        givenTrace(List.of(), List.of());

        RemediationValidationReport report = service.validate("exp_replay");

        assertThat(report.getStatus()).isEqualTo(RemediationValidationStatus.VERIFIED);
        assertThat(report.getMetricComparisons())
                .filteredOn(comparison -> "downstream.retry.amplification.factor".equals(comparison.getMetricName()))
                .extracting("matchedExpectation")
                .containsExactly(true);
    }

    @Test
    void requestCountChangeShouldBeCounterfactualInvariantViolation() {
        givenExperiments(
                ScenarioCode.RETRY_STORM,
                Map.of("requestCount", 100, "failureRatio", 0.7D, "enableRetryLimit", false),
                Map.of("requestCount", 50, "failureRatio", 0.7D, "enableRetryLimit", true)
        );

        assertServiceError(
                () -> service.validate("exp_replay"),
                ErrorCode.COUNTERFACTUAL_INVARIANT_VIOLATION
        );
    }

    @Test
    void failureRatioChangeShouldBeCounterfactualInvariantViolation() {
        givenExperiments(
                ScenarioCode.RETRY_STORM,
                Map.of("requestCount", 100, "failureRatio", 0.7D, "enableRetryLimit", false),
                Map.of("requestCount", 100, "failureRatio", 0.3D, "enableRetryLimit", true)
        );

        assertServiceError(
                () -> service.validate("exp_replay"),
                ErrorCode.COUNTERFACTUAL_INVARIANT_VIOLATION
        );
    }

    @Test
    void unknownChangedParameterShouldBeRejected() {
        givenExperiments(
                ScenarioCode.RETRY_STORM,
                Map.of("requestCount", 100, "customFlag", false),
                Map.of("requestCount", 100, "customFlag", true)
        );

        assertServiceError(
                () -> service.validate("exp_replay"),
                ErrorCode.COUNTERFACTUAL_INVARIANT_VIOLATION
        );
    }

    @Test
    void shouldGenerateTraceComparisonWithoutMixingOriginalAndReplayTrace() {
        givenExperiments(
                ScenarioCode.DOWNSTREAM_TIMEOUT,
                Map.of("requestCount", 100, "enableFallback", false),
                Map.of("requestCount", 100, "enableFallback", true)
        );
        givenMetrics(
                List.of(
                        metric("api.error.count", 80),
                        metric("downstream.fallback.count", 0),
                        metric("downstream.timeout.count", 80)
                ),
                List.of(
                        metric("api.error.count", 0),
                        metric("downstream.fallback.count", 80),
                        metric("downstream.timeout.count", 80)
                )
        );
        givenTrace(
                List.of(
                        span("trace_original", "downstream.call", TraceManager.STATUS_SUCCESS, 10),
                        span("trace_original", "downstream.timeout", TraceManager.STATUS_ERROR, 20)
                ),
                List.of(span("trace_replay", "fallback.execute", TraceManager.STATUS_SUCCESS, 5))
        );

        RemediationValidationReport report = service.validate("exp_replay");

        assertThat(report.getTraceComparison().getBeforeSpanCount()).isEqualTo(2);
        assertThat(report.getTraceComparison().getAfterSpanCount()).isEqualTo(1);
        assertThat(report.getTraceComparison().getBeforeErrorSpanCount()).isEqualTo(1);
        assertThat(report.getTraceComparison().getAfterOperationCounts()).containsEntry("fallback.execute", 1L);
    }

    private void givenExperiments(
            String scenarioCode,
            Map<String, Object> originalParams,
            Map<String, Object> replayParams
    ) {
        when(faultExperimentMapper.selectOne(any(Wrapper.class)))
                .thenReturn(experiment("exp_replay", scenarioCode, json(replayParams), "exp_original", "trace_replay"))
                .thenReturn(experiment("exp_original", scenarioCode, json(originalParams), null, "trace_original"));
    }

    private void givenMetrics(List<FaultMetric> originalMetrics, List<FaultMetric> replayMetrics) {
        when(faultMetricMapper.selectList(any(Wrapper.class)))
                .thenReturn(originalMetrics)
                .thenReturn(replayMetrics);
    }

    private void givenTrace(List<TraceSpan> originalTrace, List<TraceSpan> replayTrace) {
        when(traceSpanMapper.selectList(any(Wrapper.class)))
                .thenReturn(originalTrace)
                .thenReturn(replayTrace);
    }

    private FaultExperiment experiment(
            String experimentId,
            String scenarioCode,
            String paramsJson,
            String sourceExperimentId,
            String traceId
    ) {
        FaultExperiment experiment = new FaultExperiment();
        experiment.setExperimentId(experimentId);
        experiment.setScenarioCode(scenarioCode);
        experiment.setScenarioName(scenarioCode);
        experiment.setStatus(ExperimentStatus.RUNNING);
        experiment.setParamsJson(paramsJson);
        experiment.setSourceExperimentId(sourceExperimentId);
        experiment.setTraceId(traceId);
        return experiment;
    }

    private FaultMetric metric(String name, Number value) {
        FaultMetric metric = new FaultMetric();
        metric.setExperimentId("exp");
        metric.setMetricName(name);
        metric.setMetricValue(BigDecimal.valueOf(value.doubleValue()));
        metric.setMetricUnit("count");
        metric.setComponent("component");
        metric.setCreatedAt(LocalDateTime.now());
        return metric;
    }

    private TraceSpan span(String traceId, String operationName, String status, long durationMs) {
        TraceSpan span = new TraceSpan();
        span.setTraceId(traceId);
        span.setSpanId(operationName + "_span");
        span.setExperimentId(traceId.equals("trace_original") ? "exp_original" : "exp_replay");
        span.setOperationName(operationName);
        span.setStatus(status);
        span.setDurationMs(durationMs);
        span.setCreatedAt(LocalDateTime.now());
        return span;
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertServiceError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }
}
