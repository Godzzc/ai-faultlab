package com.faultlab.backend.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.experiment.mapper.FaultExperimentMapper;
import com.faultlab.backend.experiment.service.ExperimentRecordService;
import com.faultlab.backend.scenario.model.ExperimentStatus;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExperimentRecordServiceTests {

    private final FaultExperimentMapper faultExperimentMapper = mock(FaultExperimentMapper.class);
    private final ExperimentRecordService experimentRecordService = new ExperimentRecordService(
            faultExperimentMapper,
            new ObjectMapper()
    );

    @Test
    void shouldPersistParamsJsonAndSourceExperimentId() {
        experimentRecordService.createExperiment(
                "exp_replay",
                ScenarioCode.RETRY_STORM,
                "重试风暴",
                ExperimentStatus.RUNNING,
                "trace_replay",
                Map.of("requestCount", 100, "enableRetryLimit", true),
                "exp_original"
        );

        FaultExperiment experiment = capturedExperiment();
        assertThat(experiment.getParamsJson()).contains("\"requestCount\":100");
        assertThat(experiment.getParamsJson()).contains("\"enableRetryLimit\":true");
        assertThat(experiment.getSourceExperimentId()).isEqualTo("exp_original");
    }

    @Test
    void shouldPersistEmptyJsonWhenParamsAreNull() {
        experimentRecordService.createExperiment(
                "exp_default",
                ScenarioCode.DOWNSTREAM_TIMEOUT,
                "下游接口超时",
                ExperimentStatus.RUNNING,
                "trace_default",
                null,
                null
        );

        FaultExperiment experiment = capturedExperiment();
        assertThat(experiment.getParamsJson()).isEqualTo("{}");
        assertThat(experiment.getSourceExperimentId()).isNull();
    }

    private FaultExperiment capturedExperiment() {
        ArgumentCaptor<FaultExperiment> captor = ArgumentCaptor.forClass(FaultExperiment.class);
        verify(faultExperimentMapper).insert(captor.capture());
        return captor.getValue();
    }
}
