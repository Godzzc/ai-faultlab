package com.faultlab.backend.experiment;

import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.experiment.dto.StartExperimentRequest;
import com.faultlab.backend.experiment.dto.StartExperimentResponse;
import com.faultlab.backend.experiment.service.ExperimentRecordService;
import com.faultlab.backend.experiment.service.ExperimentService;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.cache.CacheAvalancheScenario;
import com.faultlab.backend.scenario.cache.CacheBreakdownScenario;
import com.faultlab.backend.scenario.cache.CachePenetrationScenario;
import com.faultlab.backend.scenario.db.DbConnectionPoolExhaustionScenario;
import com.faultlab.backend.scenario.db.DbLockContentionScenario;
import com.faultlab.backend.scenario.db.DbSlowQueryScenario;
import com.faultlab.backend.scenario.impl.IdempotencyConflictScenario;
import com.faultlab.backend.scenario.impl.ThreadPoolSaturationScenario;
import com.faultlab.backend.scenario.mq.MqBacklogScenario;
import com.faultlab.backend.scenario.model.ExperimentStatus;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.context.TraceContextHolder;
import com.faultlab.backend.trace.entity.TraceSpan;
import com.faultlab.backend.trace.mapper.TraceSpanMapper;
import com.faultlab.backend.trace.manager.TraceManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExperimentServiceTests {

    private final ExperimentRecordService experimentRecordService = mock(ExperimentRecordService.class);
    private final TraceSpanMapper traceSpanMapper = mock(TraceSpanMapper.class);
    private final TraceManager traceManager = new TraceManager(traceSpanMapper);
    private final FaultScenario mqBacklogScenario = mock(FaultScenario.class);
    private final FaultScenario threadPoolSaturationScenario = mock(FaultScenario.class);
    private final FaultScenario idempotencyConflictScenario = mock(FaultScenario.class);
    private final FaultScenario cachePenetrationScenario = mock(FaultScenario.class);
    private final FaultScenario cacheBreakdownScenario = mock(FaultScenario.class);
    private final FaultScenario cacheAvalancheScenario = mock(FaultScenario.class);
    private final FaultScenario dbSlowQueryScenario = mock(FaultScenario.class);
    private final FaultScenario dbLockContentionScenario = mock(FaultScenario.class);
    private final FaultScenario dbConnectionPoolExhaustionScenario = mock(FaultScenario.class);
    private ExperimentService experimentService;

    @BeforeEach
    void setUp() {
        when(mqBacklogScenario.scenarioCode()).thenReturn(ScenarioCode.MQ_BACKLOG);
        when(mqBacklogScenario.scenarioName()).thenReturn(MqBacklogScenario.SCENARIO_NAME);
        when(threadPoolSaturationScenario.scenarioCode()).thenReturn(ScenarioCode.THREAD_POOL_SATURATION);
        when(threadPoolSaturationScenario.scenarioName()).thenReturn(ThreadPoolSaturationScenario.SCENARIO_NAME);
        when(idempotencyConflictScenario.scenarioCode()).thenReturn(ScenarioCode.IDEMPOTENCY_CONFLICT);
        when(idempotencyConflictScenario.scenarioName()).thenReturn(IdempotencyConflictScenario.SCENARIO_NAME);
        when(cachePenetrationScenario.scenarioCode()).thenReturn(ScenarioCode.CACHE_PENETRATION);
        when(cachePenetrationScenario.scenarioName()).thenReturn(CachePenetrationScenario.SCENARIO_NAME);
        when(cacheBreakdownScenario.scenarioCode()).thenReturn(ScenarioCode.CACHE_BREAKDOWN);
        when(cacheBreakdownScenario.scenarioName()).thenReturn(CacheBreakdownScenario.SCENARIO_NAME);
        when(cacheAvalancheScenario.scenarioCode()).thenReturn(ScenarioCode.CACHE_AVALANCHE);
        when(cacheAvalancheScenario.scenarioName()).thenReturn(CacheAvalancheScenario.SCENARIO_NAME);
        when(dbSlowQueryScenario.scenarioCode()).thenReturn(ScenarioCode.DB_SLOW_QUERY);
        when(dbSlowQueryScenario.scenarioName()).thenReturn(DbSlowQueryScenario.SCENARIO_NAME);
        when(dbLockContentionScenario.scenarioCode()).thenReturn(ScenarioCode.DB_LOCK_CONTENTION);
        when(dbLockContentionScenario.scenarioName()).thenReturn(DbLockContentionScenario.SCENARIO_NAME);
        when(dbConnectionPoolExhaustionScenario.scenarioCode()).thenReturn(ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION);
        when(dbConnectionPoolExhaustionScenario.scenarioName()).thenReturn(DbConnectionPoolExhaustionScenario.SCENARIO_NAME);
        experimentService = new ExperimentService(
                experimentRecordService,
                traceManager,
                List.of(
                        mqBacklogScenario,
                        threadPoolSaturationScenario,
                        idempotencyConflictScenario,
                        cachePenetrationScenario,
                        cacheBreakdownScenario,
                        cacheAvalancheScenario,
                        dbSlowQueryScenario,
                        dbLockContentionScenario,
                        dbConnectionPoolExhaustionScenario
                )
        );
    }

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    @Test
    void shouldStartMqBacklogExperiment() {
        StartExperimentRequest request = new StartExperimentRequest();
        request.setScenarioCode(ScenarioCode.MQ_BACKLOG);
        request.setParams(Map.of("messageCount", 3, "consumerDelayMs", 0));

        StartExperimentResponse response = experimentService.startExperiment(request);

        assertThat(response.getExperimentId()).startsWith("exp_");
        assertThat(response.getTraceId()).isNotBlank();
        assertThat(response.getStatus()).isEqualTo(ExperimentStatus.RUNNING);
        verify(experimentRecordService).createExperiment(
                eq(response.getExperimentId()),
                eq(ScenarioCode.MQ_BACKLOG),
                eq(MqBacklogScenario.SCENARIO_NAME),
                eq(ExperimentStatus.RUNNING),
                eq(response.getTraceId())
        );
        verify(mqBacklogScenario).execute(eq(response.getExperimentId()), eq(request.getParams()));
        verify(traceSpanMapper).insert(any(TraceSpan.class));
        assertThat(TraceContextHolder.get()).isNull();
    }

    @Test
    void shouldStartThreadPoolSaturationScenario() {
        StartExperimentRequest request = new StartExperimentRequest();
        request.setScenarioCode(ScenarioCode.THREAD_POOL_SATURATION);
        request.setParams(Map.of("taskCount", 30, "taskSleepMs", 3000));

        StartExperimentResponse response = experimentService.startExperiment(request);

        assertThat(response.getExperimentId()).startsWith("exp_");
        assertThat(response.getTraceId()).isNotBlank();
        assertThat(response.getStatus()).isEqualTo(ExperimentStatus.RUNNING);
        verify(experimentRecordService).createExperiment(
                eq(response.getExperimentId()),
                eq(ScenarioCode.THREAD_POOL_SATURATION),
                eq(ThreadPoolSaturationScenario.SCENARIO_NAME),
                eq(ExperimentStatus.RUNNING),
                eq(response.getTraceId())
        );
        verify(threadPoolSaturationScenario).execute(eq(response.getExperimentId()), eq(request.getParams()));
        assertThat(TraceContextHolder.get()).isNull();
    }

    @Test
    void shouldStartIdempotencyConflictScenario() {
        StartExperimentRequest request = new StartExperimentRequest();
        request.setScenarioCode(ScenarioCode.IDEMPOTENCY_CONFLICT);
        request.setParams(Map.of("requestCount", 30, "duplicateCount", 20, "conflictCount", 8));

        StartExperimentResponse response = experimentService.startExperiment(request);

        assertThat(response.getExperimentId()).startsWith("exp_");
        assertThat(response.getTraceId()).isNotBlank();
        assertThat(response.getStatus()).isEqualTo(ExperimentStatus.RUNNING);
        verify(experimentRecordService).createExperiment(
                eq(response.getExperimentId()),
                eq(ScenarioCode.IDEMPOTENCY_CONFLICT),
                eq(IdempotencyConflictScenario.SCENARIO_NAME),
                eq(ExperimentStatus.RUNNING),
                eq(response.getTraceId())
        );
        verify(idempotencyConflictScenario).execute(eq(response.getExperimentId()), eq(request.getParams()));
        assertThat(TraceContextHolder.get()).isNull();
    }

    @Test
    void shouldStartCachePenetrationScenario() {
        StartExperimentRequest request = new StartExperimentRequest();
        request.setScenarioCode(ScenarioCode.CACHE_PENETRATION);
        request.setParams(Map.of("requestCount", 100, "invalidKeyRatio", 0.8));

        StartExperimentResponse response = experimentService.startExperiment(request);

        assertThat(response.getExperimentId()).startsWith("exp_");
        assertThat(response.getTraceId()).isNotBlank();
        verify(experimentRecordService).createExperiment(
                eq(response.getExperimentId()),
                eq(ScenarioCode.CACHE_PENETRATION),
                eq(CachePenetrationScenario.SCENARIO_NAME),
                eq(ExperimentStatus.RUNNING),
                eq(response.getTraceId())
        );
        verify(cachePenetrationScenario).execute(eq(response.getExperimentId()), eq(request.getParams()));
    }

    @Test
    void shouldStartCacheBreakdownScenario() {
        StartExperimentRequest request = new StartExperimentRequest();
        request.setScenarioCode(ScenarioCode.CACHE_BREAKDOWN);
        request.setParams(Map.of("requestCount", 100, "hotKey", "hot:item:1", "concurrency", 20));

        StartExperimentResponse response = experimentService.startExperiment(request);

        assertThat(response.getExperimentId()).startsWith("exp_");
        assertThat(response.getTraceId()).isNotBlank();
        verify(experimentRecordService).createExperiment(
                eq(response.getExperimentId()),
                eq(ScenarioCode.CACHE_BREAKDOWN),
                eq(CacheBreakdownScenario.SCENARIO_NAME),
                eq(ExperimentStatus.RUNNING),
                eq(response.getTraceId())
        );
        verify(cacheBreakdownScenario).execute(eq(response.getExperimentId()), eq(request.getParams()));
    }

    @Test
    void shouldStartCacheAvalancheScenario() {
        StartExperimentRequest request = new StartExperimentRequest();
        request.setScenarioCode(ScenarioCode.CACHE_AVALANCHE);
        request.setParams(Map.of("keyCount", 50, "requestCount", 200, "sameTtl", true));

        StartExperimentResponse response = experimentService.startExperiment(request);

        assertThat(response.getExperimentId()).startsWith("exp_");
        assertThat(response.getTraceId()).isNotBlank();
        verify(experimentRecordService).createExperiment(
                eq(response.getExperimentId()),
                eq(ScenarioCode.CACHE_AVALANCHE),
                eq(CacheAvalancheScenario.SCENARIO_NAME),
                eq(ExperimentStatus.RUNNING),
                eq(response.getTraceId())
        );
        verify(cacheAvalancheScenario).execute(eq(response.getExperimentId()), eq(request.getParams()));
    }

    @Test
    void shouldStartDbSlowQueryScenario() {
        StartExperimentRequest request = new StartExperimentRequest();
        request.setScenarioCode(ScenarioCode.DB_SLOW_QUERY);
        request.setParams(Map.of("requestCount", 100, "queryMode", "FULL_SCAN"));

        StartExperimentResponse response = experimentService.startExperiment(request);

        assertThat(response.getExperimentId()).startsWith("exp_");
        assertThat(response.getTraceId()).isNotBlank();
        verify(experimentRecordService).createExperiment(
                eq(response.getExperimentId()),
                eq(ScenarioCode.DB_SLOW_QUERY),
                eq(DbSlowQueryScenario.SCENARIO_NAME),
                eq(ExperimentStatus.RUNNING),
                eq(response.getTraceId())
        );
        verify(dbSlowQueryScenario).execute(eq(response.getExperimentId()), eq(request.getParams()));
    }

    @Test
    void shouldStartDbLockContentionScenario() {
        StartExperimentRequest request = new StartExperimentRequest();
        request.setScenarioCode(ScenarioCode.DB_LOCK_CONTENTION);
        request.setParams(Map.of("requestCount", 50, "concurrency", 10, "targetRowId", "order:1"));

        StartExperimentResponse response = experimentService.startExperiment(request);

        assertThat(response.getExperimentId()).startsWith("exp_");
        assertThat(response.getTraceId()).isNotBlank();
        verify(experimentRecordService).createExperiment(
                eq(response.getExperimentId()),
                eq(ScenarioCode.DB_LOCK_CONTENTION),
                eq(DbLockContentionScenario.SCENARIO_NAME),
                eq(ExperimentStatus.RUNNING),
                eq(response.getTraceId())
        );
        verify(dbLockContentionScenario).execute(eq(response.getExperimentId()), eq(request.getParams()));
    }

    @Test
    void shouldStartDbConnectionPoolExhaustionScenario() {
        StartExperimentRequest request = new StartExperimentRequest();
        request.setScenarioCode(ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION);
        request.setParams(Map.of("requestCount", 100, "concurrency", 30, "maxPoolSize", 10));

        StartExperimentResponse response = experimentService.startExperiment(request);

        assertThat(response.getExperimentId()).startsWith("exp_");
        assertThat(response.getTraceId()).isNotBlank();
        verify(experimentRecordService).createExperiment(
                eq(response.getExperimentId()),
                eq(ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION),
                eq(DbConnectionPoolExhaustionScenario.SCENARIO_NAME),
                eq(ExperimentStatus.RUNNING),
                eq(response.getTraceId())
        );
        verify(dbConnectionPoolExhaustionScenario).execute(eq(response.getExperimentId()), eq(request.getParams()));
    }

    @Test
    void shouldUseExperimentTraceIdForStartExperimentFlow() {
        StartExperimentRequest request = new StartExperimentRequest();
        request.setScenarioCode(ScenarioCode.MQ_BACKLOG);
        AtomicReference<String> traceIdDuringScenario = new AtomicReference<>();
        doAnswer(invocation -> {
            traceIdDuringScenario.set(TraceContextHolder.get().getTraceId());
            return null;
        }).when(mqBacklogScenario).execute(anyString(), any());

        StartExperimentResponse response = experimentService.startExperiment(request);

        assertThat(traceIdDuringScenario.get()).isEqualTo(response.getTraceId());
    }

    @Test
    void shouldRejectUnsupportedScenarioCode() {
        StartExperimentRequest request = new StartExperimentRequest();
        request.setScenarioCode("UNKNOWN_SCENARIO");

        assertThatThrownBy(() -> experimentService.startExperiment(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unsupported scenarioCode");
    }
}
