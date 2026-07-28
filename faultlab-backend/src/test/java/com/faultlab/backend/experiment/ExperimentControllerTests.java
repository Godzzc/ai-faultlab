package com.faultlab.backend.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.faultlab.backend.experiment.dto.StartExperimentRequest;
import com.faultlab.backend.experiment.dto.StartExperimentResponse;
import com.faultlab.backend.experiment.service.ExperimentService;
import com.faultlab.backend.scenario.model.ExperimentStatus;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(com.faultlab.backend.experiment.controller.ExperimentController.class)
class ExperimentControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ExperimentService experimentService;

    @Test
    void shouldStartMqBacklogExperiment() throws Exception {
        StartExperimentRequest request = new StartExperimentRequest();
        request.setScenarioCode(ScenarioCode.MQ_BACKLOG);
        request.setParams(Map.of("messageCount", 3, "consumerDelayMs", 0));
        when(experimentService.startExperiment(any(StartExperimentRequest.class)))
                .thenReturn(new StartExperimentResponse("exp_123", "trace_123", ExperimentStatus.RUNNING));

        mockMvc.perform(post("/api/experiments/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.experimentId").value("exp_123"))
                .andExpect(jsonPath("$.data.traceId").value("trace_123"))
                .andExpect(jsonPath("$.data.status").value(ExperimentStatus.RUNNING));
    }

    @Test
    void shouldStartThreadPoolSaturationExperiment() throws Exception {
        StartExperimentRequest request = new StartExperimentRequest();
        request.setScenarioCode(ScenarioCode.THREAD_POOL_SATURATION);
        request.setParams(Map.of("taskCount", 30, "taskSleepMs", 3000));
        when(experimentService.startExperiment(any(StartExperimentRequest.class)))
                .thenReturn(new StartExperimentResponse("exp_threadpool", "trace_threadpool", ExperimentStatus.RUNNING));

        mockMvc.perform(post("/api/experiments/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.experimentId").value("exp_threadpool"))
                .andExpect(jsonPath("$.data.traceId").value("trace_threadpool"))
                .andExpect(jsonPath("$.data.status").value(ExperimentStatus.RUNNING));
    }

    @Test
    void shouldStartIdempotencyConflictExperiment() throws Exception {
        StartExperimentRequest request = new StartExperimentRequest();
        request.setScenarioCode(ScenarioCode.IDEMPOTENCY_CONFLICT);
        request.setParams(Map.of("requestCount", 30, "duplicateCount", 20, "conflictCount", 8));
        when(experimentService.startExperiment(any(StartExperimentRequest.class)))
                .thenReturn(new StartExperimentResponse("exp_idempotency", "trace_idempotency", ExperimentStatus.RUNNING));

        mockMvc.perform(post("/api/experiments/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.experimentId").value("exp_idempotency"))
                .andExpect(jsonPath("$.data.traceId").value("trace_idempotency"))
                .andExpect(jsonPath("$.data.status").value(ExperimentStatus.RUNNING));
    }
}
