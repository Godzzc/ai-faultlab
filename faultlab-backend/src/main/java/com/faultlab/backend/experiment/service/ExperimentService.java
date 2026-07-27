package com.faultlab.backend.experiment.service;

import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.common.ErrorCode;
import com.faultlab.backend.experiment.dto.StartExperimentRequest;
import com.faultlab.backend.experiment.dto.StartExperimentResponse;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.model.ExperimentStatus;
import com.faultlab.backend.trace.context.TraceContextHolder;
import com.faultlab.backend.trace.manager.TraceManager;
import com.faultlab.backend.trace.model.TraceSpanRecord;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ExperimentService {

    private final ExperimentRecordService experimentRecordService;
    private final TraceManager traceManager;
    private final Map<String, FaultScenario> scenarioByCode;

    public ExperimentService(
            ExperimentRecordService experimentRecordService,
            TraceManager traceManager,
            List<FaultScenario> scenarios
    ) {
        this.experimentRecordService = experimentRecordService;
        this.traceManager = traceManager;
        this.scenarioByCode = scenarios.stream()
                .collect(Collectors.toUnmodifiableMap(FaultScenario::scenarioCode, Function.identity()));
    }

    public StartExperimentResponse startExperiment(StartExperimentRequest request) {
        if (request == null || !StringUtils.hasText(request.getScenarioCode())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "scenarioCode is required");
        }

        FaultScenario scenario = scenarioByCode.get(request.getScenarioCode());
        if (scenario == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "unsupported scenarioCode: " + request.getScenarioCode());
        }

        String experimentId = createExperimentId();
        TraceSpanRecord rootSpan = traceManager.startRootSpan(experimentId, "experiment.start", "FaultLab");
        String traceId = rootSpan.getTraceId();

        try {
            experimentRecordService.createExperiment(
                    experimentId,
                    scenario.scenarioCode(),
                    scenario.scenarioName(),
                    ExperimentStatus.RUNNING,
                    traceId
            );
            scenario.execute(experimentId, request.getParams());
            traceManager.finishSpan(rootSpan);
            return new StartExperimentResponse(experimentId, traceId, ExperimentStatus.RUNNING);
        } catch (RuntimeException exception) {
            traceManager.finishSpan(rootSpan, exception);
            throw exception;
        } finally {
            TraceContextHolder.clear();
        }
    }

    private String createExperimentId() {
        return "exp_" + UUID.randomUUID().toString().replace("-", "");
    }
}
