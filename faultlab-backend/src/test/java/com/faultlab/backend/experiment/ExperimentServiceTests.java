package com.faultlab.backend.experiment;

import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.experiment.dto.StartExperimentRequest;
import com.faultlab.backend.experiment.dto.StartExperimentResponse;
import com.faultlab.backend.experiment.service.ExperimentRecordService;
import com.faultlab.backend.experiment.service.ExperimentService;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.mq.MqBacklogScenario;
import com.faultlab.backend.scenario.model.ExperimentStatus;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.context.TraceContextHolder;
import com.faultlab.backend.trace.entity.TraceSpan;
import com.faultlab.backend.trace.mapper.TraceSpanMapper;
import com.faultlab.backend.trace.manager.TraceManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExperimentServiceTests {

    private final ExperimentRecordService experimentRecordService = mock(ExperimentRecordService.class);
    private final TraceSpanMapper traceSpanMapper = mock(TraceSpanMapper.class);
    private final TraceManager traceManager = new TraceManager(traceSpanMapper);
    private final FaultScenario mqBacklogScenario = mock(FaultScenario.class);
    private ExperimentService experimentService;

    @BeforeEach
    void setUp() {
        when(mqBacklogScenario.scenarioCode()).thenReturn(ScenarioCode.MQ_BACKLOG);
        when(mqBacklogScenario.scenarioName()).thenReturn(MqBacklogScenario.SCENARIO_NAME);
        experimentService = new ExperimentService(
                experimentRecordService,
                traceManager,
                List.of(mqBacklogScenario)
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
    void shouldRejectUnsupportedScenarioCode() {
        StartExperimentRequest request = new StartExperimentRequest();
        request.setScenarioCode("THREAD_POOL_SATURATION");

        assertThatThrownBy(() -> experimentService.startExperiment(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unsupported scenarioCode");
    }
}
