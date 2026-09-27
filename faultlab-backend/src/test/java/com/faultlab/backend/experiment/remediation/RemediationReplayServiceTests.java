package com.faultlab.backend.experiment.remediation;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.common.ErrorCode;
import com.faultlab.backend.experiment.dto.RemediationReplayRequest;
import com.faultlab.backend.experiment.dto.RemediationReplayResponse;
import com.faultlab.backend.experiment.dto.StartExperimentResponse;
import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.experiment.mapper.FaultExperimentMapper;
import com.faultlab.backend.experiment.service.ExperimentRecordService;
import com.faultlab.backend.experiment.service.ExperimentService;
import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.cache.CacheBreakdownScenario;
import com.faultlab.backend.scenario.db.DbConnectionPoolExhaustionScenario;
import com.faultlab.backend.scenario.downstream.RetryStormScenario;
import com.faultlab.backend.scenario.downstream.DownstreamTimeoutScenario;
import com.faultlab.backend.scenario.model.ExperimentStatus;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.context.TraceContextHolder;
import com.faultlab.backend.trace.entity.TraceSpan;
import com.faultlab.backend.trace.mapper.TraceSpanMapper;
import com.faultlab.backend.trace.manager.TraceManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemediationReplayServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FaultExperimentMapper faultExperimentMapper = mock(FaultExperimentMapper.class);
    private final ExperimentService experimentService = mock(ExperimentService.class);
    private final RemediationReplayService replayService = new RemediationReplayService(
            faultExperimentMapper,
            experimentService,
            new RemediationReplayPolicy(),
            objectMapper
    );

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    @Test
    void shouldRejectWhenOriginalExperimentDoesNotExist() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertServiceError(
                () -> replayService.replay("exp_missing", request(null, Map.of("enableFallback", true))),
                ErrorCode.ORIGINAL_EXPERIMENT_NOT_FOUND
        );
    }

    @Test
    void shouldRejectWhenOriginalExperimentParamsUnavailable() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(
                "exp_original",
                ScenarioCode.DOWNSTREAM_TIMEOUT,
                null
        ));

        assertServiceError(
                () -> replayService.replay("exp_original", request(null, Map.of("enableFallback", true))),
                ErrorCode.ORIGINAL_EXPERIMENT_PARAMS_UNAVAILABLE
        );
    }

    @Test
    void shouldRejectScenarioMismatch() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(
                "exp_original",
                ScenarioCode.RETRY_STORM,
                json(Map.of("requestCount", 100))
        ));

        assertServiceError(
                () -> replayService.replay(
                        "exp_original",
                        request(ScenarioCode.CACHE_BREAKDOWN, Map.of("enableRetryLimit", true))
                ),
                ErrorCode.SCENARIO_MISMATCH
        );
    }

    @Test
    void shouldReplaySupportedScenarioAndGenerateNewExperimentId() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(
                "exp_original",
                ScenarioCode.CACHE_BREAKDOWN,
                json(Map.of("requestCount", 100, "concurrency", 20, "enableMutex", false))
        ));
        when(experimentService.startReplayExperiment(eq(ScenarioCode.CACHE_BREAKDOWN), any(), eq("exp_original")))
                .thenReturn(new StartExperimentResponse("exp_replay", "trace_replay", ExperimentStatus.RUNNING));

        RemediationReplayResponse response = replayService.replay(
                "exp_original",
                request(null, Map.of("enableMutex", true))
        );

        assertThat(response.getOriginalExperimentId()).isEqualTo("exp_original");
        assertThat(response.getReplayExperimentId()).isEqualTo("exp_replay");
        assertThat(response.getReplayExperimentId()).isNotEqualTo(response.getOriginalExperimentId());
        assertThat(response.getStatus()).isEqualTo(RemediationReplayStatus.COMPLETED);
    }

    @Test
    void shouldKeepOriginalExperimentIdAndOriginalParamsUnmodified() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(
                "exp_original",
                ScenarioCode.CACHE_BREAKDOWN,
                json(Map.of("requestCount", 100, "concurrency", 20, "enableMutex", false))
        ));
        when(experimentService.startReplayExperiment(eq(ScenarioCode.CACHE_BREAKDOWN), any(), eq("exp_original")))
                .thenReturn(new StartExperimentResponse("exp_replay", "trace_replay", ExperimentStatus.RUNNING));

        RemediationReplayResponse response = replayService.replay(
                "exp_original",
                request(null, Map.of("enableMutex", true))
        );

        assertThat(response.getOriginalExperimentId()).isEqualTo("exp_original");
        assertThat(response.getOriginalParams())
                .containsEntry("requestCount", 100)
                .containsEntry("concurrency", 20)
                .containsEntry("enableMutex", false);
    }

    @Test
    void cacheBreakdownShouldApplyMutexAndKeepOtherParams() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(
                "exp_original",
                ScenarioCode.CACHE_BREAKDOWN,
                json(Map.of("requestCount", 100, "hotKey", "hot:item:1", "concurrency", 20, "enableMutex", false))
        ));
        when(experimentService.startReplayExperiment(eq(ScenarioCode.CACHE_BREAKDOWN), any(), eq("exp_original")))
                .thenReturn(new StartExperimentResponse("exp_replay", "trace_replay", ExperimentStatus.RUNNING));

        RemediationReplayResponse response = replayService.replay(
                "exp_original",
                request(null, Map.of("enableMutex", true))
        );

        assertThat(response.getAppliedPatch()).containsEntry("enableMutex", true);
        assertThat(response.getReplayParams())
                .containsEntry("requestCount", 100)
                .containsEntry("hotKey", "hot:item:1")
                .containsEntry("concurrency", 20)
                .containsEntry("enableMutex", true);
    }

    @Test
    void dbConnectionPoolShouldApplyFastRelease() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(
                "exp_original",
                ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION,
                json(Map.of("requestCount", 100, "concurrency", 30, "enableFastRelease", false))
        ));
        when(experimentService.startReplayExperiment(eq(ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION), any(), eq("exp_original")))
                .thenReturn(new StartExperimentResponse("exp_replay", "trace_replay", ExperimentStatus.RUNNING));

        RemediationReplayResponse response = replayService.replay(
                "exp_original",
                request(null, Map.of("enableFastRelease", true))
        );

        assertThat(response.getReplayParams()).containsEntry("enableFastRelease", true);
    }

    @Test
    void downstreamTimeoutShouldApplyFallback() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(
                "exp_original",
                ScenarioCode.DOWNSTREAM_TIMEOUT,
                json(Map.of("requestCount", 100, "concurrency", 20, "enableFallback", false))
        ));
        when(experimentService.startReplayExperiment(eq(ScenarioCode.DOWNSTREAM_TIMEOUT), any(), eq("exp_original")))
                .thenReturn(new StartExperimentResponse("exp_replay", "trace_replay", ExperimentStatus.RUNNING));

        RemediationReplayResponse response = replayService.replay(
                "exp_original",
                request(null, Map.of("enableFallback", true))
        );

        assertThat(response.getReplayParams()).containsEntry("enableFallback", true);
    }

    @Test
    void retryStormShouldApplyRetryLimitAndJitterAndKeepFaultConditions() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(
                "exp_original",
                ScenarioCode.RETRY_STORM,
                json(Map.of(
                        "requestCount", 100,
                        "concurrency", 20,
                        "failureRatio", 0.7D,
                        "maxRetries", 3,
                        "enableRetryLimit", false,
                        "enableJitter", false
                ))
        ));
        when(experimentService.startReplayExperiment(eq(ScenarioCode.RETRY_STORM), any(), eq("exp_original")))
                .thenReturn(new StartExperimentResponse("exp_replay", "trace_replay", ExperimentStatus.RUNNING));

        RemediationReplayResponse response = replayService.replay(
                "exp_original",
                request(null, Map.of("enableRetryLimit", true, "enableJitter", true))
        );

        assertThat(response.getReplayParams())
                .containsEntry("enableRetryLimit", true)
                .containsEntry("enableJitter", true)
                .containsEntry("failureRatio", 0.7D)
                .containsEntry("requestCount", 100)
                .containsEntry("concurrency", 20);
    }

    @Test
    void shouldRejectUnsupportedMqBacklogScenario() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(
                "exp_original",
                ScenarioCode.MQ_BACKLOG,
                json(Map.of("messageCount", 100))
        ));

        assertServiceError(
                () -> replayService.replay("exp_original", request(null, Map.of("enableRetryLimit", true))),
                ErrorCode.REMEDIATION_REPLAY_UNSUPPORTED_SCENARIO
        );
    }

    @Test
    void shouldNotExecuteReplayWhenPatchIsRejected() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(
                "exp_original",
                ScenarioCode.RETRY_STORM,
                json(Map.of("failureRatio", 0.7D))
        ));

        assertServiceError(
                () -> replayService.replay("exp_original", request(null, Map.of("failureRatio", 0.1D))),
                ErrorCode.REMEDIATION_PARAMETER_NOT_ALLOWED
        );
        verify(experimentService, never()).startReplayExperiment(any(), any(), any());
    }

    @Test
    void shouldMapReplayExecutionFailure() {
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(
                "exp_original",
                ScenarioCode.DOWNSTREAM_TIMEOUT,
                json(Map.of("enableFallback", false))
        ));
        when(experimentService.startReplayExperiment(eq(ScenarioCode.DOWNSTREAM_TIMEOUT), any(), eq("exp_original")))
                .thenThrow(new RuntimeException("scenario failed"));

        assertServiceError(
                () -> replayService.replay("exp_original", request(null, Map.of("enableFallback", true))),
                ErrorCode.REPLAY_EXECUTION_FAILED
        );
    }

    @Test
    void replayExperimentShouldGenerateMetricsWithReplayExperimentId() {
        MetricService metricService = mock(MetricService.class);
        TraceSpanMapper traceSpanMapper = mock(TraceSpanMapper.class);
        TraceManager traceManager = new TraceManager(traceSpanMapper);
        FaultScenario retryStormScenario = new RetryStormScenario(metricService, traceManager);
        ExperimentRecordService experimentRecordService = mock(ExperimentRecordService.class);
        ExperimentService realExperimentService = new ExperimentService(
                experimentRecordService,
                traceManager,
                List.of(retryStormScenario)
        );
        RemediationReplayService realReplayService = new RemediationReplayService(
                faultExperimentMapper,
                realExperimentService,
                new RemediationReplayPolicy(),
                objectMapper
        );
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(
                "exp_original",
                ScenarioCode.RETRY_STORM,
                json(Map.of(
                        "requestCount", 20,
                        "concurrency", 5,
                        "failureRatio", 0.7D,
                        "maxRetries", 3,
                        "enableRetryLimit", false,
                        "enableJitter", false
                ))
        ));

        RemediationReplayResponse response = realReplayService.replay(
                "exp_original",
                request(null, Map.of("enableRetryLimit", true, "enableJitter", true))
        );

        ArgumentCaptor<String> experimentIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(metricService, atLeastOnce()).recordMetric(
                experimentIdCaptor.capture(),
                any(),
                any(),
                any(),
                any()
        );
        assertThat(experimentIdCaptor.getAllValues())
                .allMatch(response.getReplayExperimentId()::equals)
                .doesNotContain("exp_original");
    }

    @Test
    void replayExperimentShouldGenerateTraceWithReplayExperimentId() {
        MetricService metricService = mock(MetricService.class);
        TraceSpanMapper traceSpanMapper = mock(TraceSpanMapper.class);
        TraceManager traceManager = new TraceManager(traceSpanMapper);
        FaultScenario retryStormScenario = new RetryStormScenario(metricService, traceManager);
        ExperimentService realExperimentService = new ExperimentService(
                mock(ExperimentRecordService.class),
                traceManager,
                List.of(retryStormScenario)
        );
        RemediationReplayService realReplayService = new RemediationReplayService(
                faultExperimentMapper,
                realExperimentService,
                new RemediationReplayPolicy(),
                objectMapper
        );
        when(faultExperimentMapper.selectOne(any(Wrapper.class))).thenReturn(experiment(
                "exp_original",
                ScenarioCode.RETRY_STORM,
                json(Map.of("requestCount", 20, "failureRatio", 0.7D, "maxRetries", 3))
        ));

        RemediationReplayResponse response = realReplayService.replay(
                "exp_original",
                request(null, Map.of("enableRetryLimit", true))
        );

        ArgumentCaptor<TraceSpan> spanCaptor = ArgumentCaptor.forClass(TraceSpan.class);
        verify(traceSpanMapper, atLeastOnce()).insert(spanCaptor.capture());
        assertThat(spanCaptor.getAllValues())
                .extracting(TraceSpan::getExperimentId)
                .allMatch(response.getReplayExperimentId()::equals);
    }

    private RemediationReplayRequest request(String scenarioCode, Map<String, Object> patch) {
        RemediationReplayRequest request = new RemediationReplayRequest();
        request.setPlanId("plan_1");
        request.setScenarioCode(scenarioCode);
        request.setParameterPatch(patch);
        return request;
    }

    private FaultExperiment experiment(String experimentId, String scenarioCode, String paramsJson) {
        FaultExperiment experiment = new FaultExperiment();
        experiment.setExperimentId(experimentId);
        experiment.setScenarioCode(scenarioCode);
        experiment.setScenarioName(scenarioCode);
        experiment.setStatus(ExperimentStatus.RUNNING);
        experiment.setTraceId("trace_original");
        experiment.setParamsJson(paramsJson);
        return experiment;
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
