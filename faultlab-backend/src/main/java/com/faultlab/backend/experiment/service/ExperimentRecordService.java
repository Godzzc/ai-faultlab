package com.faultlab.backend.experiment.service;

import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.experiment.mapper.FaultExperimentMapper;
import com.faultlab.backend.trace.annotation.TraceSpan;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class ExperimentRecordService {

    private final FaultExperimentMapper faultExperimentMapper;

    public ExperimentRecordService(FaultExperimentMapper faultExperimentMapper) {
        this.faultExperimentMapper = faultExperimentMapper;
    }

    @TraceSpan(operationName = "experiment.create", component = "MySQL")
    public void createExperiment(String experimentId, String scenarioCode, String scenarioName, String status, String traceId) {
        LocalDateTime now = LocalDateTime.now();
        FaultExperiment experiment = new FaultExperiment();
        experiment.setExperimentId(experimentId);
        experiment.setScenarioCode(scenarioCode);
        experiment.setScenarioName(scenarioName);
        experiment.setStatus(status);
        experiment.setTraceId(traceId);
        experiment.setStartTime(now);
        experiment.setCreatedAt(now);
        experiment.setUpdatedAt(now);
        faultExperimentMapper.insert(experiment);
    }
}
