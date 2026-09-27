package com.faultlab.backend.experiment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.common.ErrorCode;
import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.experiment.mapper.FaultExperimentMapper;
import com.faultlab.backend.trace.annotation.TraceSpan;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ExperimentRecordService {

    private final FaultExperimentMapper faultExperimentMapper;
    private final ObjectMapper objectMapper;

    public ExperimentRecordService(FaultExperimentMapper faultExperimentMapper, ObjectMapper objectMapper) {
        this.faultExperimentMapper = faultExperimentMapper;
        this.objectMapper = objectMapper;
    }

    @TraceSpan(operationName = "experiment.create", component = "MySQL")
    public void createExperiment(String experimentId, String scenarioCode, String scenarioName, String status, String traceId) {
        createExperiment(experimentId, scenarioCode, scenarioName, status, traceId, null, null);
    }

    @TraceSpan(operationName = "experiment.create", component = "MySQL")
    public void createExperiment(
            String experimentId,
            String scenarioCode,
            String scenarioName,
            String status,
            String traceId,
            Map<String, Object> params,
            String sourceExperimentId
    ) {
        LocalDateTime now = LocalDateTime.now();
        FaultExperiment experiment = new FaultExperiment();
        experiment.setExperimentId(experimentId);
        experiment.setScenarioCode(scenarioCode);
        experiment.setScenarioName(scenarioName);
        experiment.setStatus(status);
        experiment.setTraceId(traceId);
        experiment.setParamsJson(serializeParams(params));
        experiment.setSourceExperimentId(sourceExperimentId);
        experiment.setStartTime(now);
        experiment.setCreatedAt(now);
        experiment.setUpdatedAt(now);
        faultExperimentMapper.insert(experiment);
    }

    private String serializeParams(Map<String, Object> params) {
        if (params == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "failed to serialize experiment params");
        }
    }
}
