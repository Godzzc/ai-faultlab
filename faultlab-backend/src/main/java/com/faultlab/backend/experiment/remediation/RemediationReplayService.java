package com.faultlab.backend.experiment.remediation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.common.ErrorCode;
import com.faultlab.backend.experiment.dto.RemediationReplayRequest;
import com.faultlab.backend.experiment.dto.RemediationReplayResponse;
import com.faultlab.backend.experiment.dto.StartExperimentResponse;
import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.experiment.mapper.FaultExperimentMapper;
import com.faultlab.backend.experiment.service.ExperimentService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RemediationReplayService {

    private static final TypeReference<LinkedHashMap<String, Object>> PARAMS_TYPE = new TypeReference<>() {
    };

    private final FaultExperimentMapper faultExperimentMapper;
    private final ExperimentService experimentService;
    private final RemediationReplayPolicy remediationReplayPolicy;
    private final ObjectMapper objectMapper;

    public RemediationReplayService(
            FaultExperimentMapper faultExperimentMapper,
            ExperimentService experimentService,
            RemediationReplayPolicy remediationReplayPolicy,
            ObjectMapper objectMapper
    ) {
        this.faultExperimentMapper = faultExperimentMapper;
        this.experimentService = experimentService;
        this.remediationReplayPolicy = remediationReplayPolicy;
        this.objectMapper = objectMapper;
    }

    public RemediationReplayResponse replay(String originalExperimentId, RemediationReplayRequest request) {
        if (!StringUtils.hasText(originalExperimentId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "original experimentId is required");
        }
        if (request == null) {
            throw new BusinessException(ErrorCode.INVALID_REMEDIATION_PATCH, "remediation replay request is required");
        }

        FaultExperiment originalExperiment = findOriginalExperiment(originalExperimentId);
        String scenarioCode = originalExperiment.getScenarioCode();
        if (StringUtils.hasText(request.getScenarioCode()) && !scenarioCode.equals(request.getScenarioCode())) {
            throw new BusinessException(
                    ErrorCode.SCENARIO_MISMATCH,
                    "request scenarioCode does not match original experiment scenarioCode"
            );
        }

        Map<String, Object> originalParams = readOriginalParams(originalExperiment);
        Map<String, Object> appliedPatch = remediationReplayPolicy.validateAndNormalize(
                scenarioCode,
                request.getParameterPatch()
        );
        Map<String, Object> replayParams = buildReplayParams(originalParams, appliedPatch);

        StartExperimentResponse replay;
        try {
            replay = experimentService.startReplayExperiment(scenarioCode, replayParams, originalExperimentId);
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.REPLAY_EXECUTION_FAILED, "remediation replay execution failed");
        }

        RemediationReplayResponse response = new RemediationReplayResponse();
        response.setPlanId(request.getPlanId());
        response.setOriginalExperimentId(originalExperimentId);
        response.setReplayExperimentId(replay.getExperimentId());
        response.setScenarioCode(scenarioCode);
        response.setOriginalParams(originalParams);
        response.setAppliedPatch(appliedPatch);
        response.setReplayParams(replayParams);
        response.setStatus(RemediationReplayStatus.COMPLETED);
        response.setCreatedAt(LocalDateTime.now());
        return response;
    }

    private FaultExperiment findOriginalExperiment(String originalExperimentId) {
        FaultExperiment experiment = faultExperimentMapper.selectOne(new LambdaQueryWrapper<FaultExperiment>()
                .eq(FaultExperiment::getExperimentId, originalExperimentId)
                .last("LIMIT 1"));
        if (experiment == null) {
            throw new BusinessException(
                    ErrorCode.ORIGINAL_EXPERIMENT_NOT_FOUND,
                    "original experiment not found: " + originalExperimentId
            );
        }
        return experiment;
    }

    private Map<String, Object> readOriginalParams(FaultExperiment experiment) {
        if (!StringUtils.hasText(experiment.getParamsJson())) {
            throw new BusinessException(
                    ErrorCode.ORIGINAL_EXPERIMENT_PARAMS_UNAVAILABLE,
                    "original experiment params unavailable: " + experiment.getExperimentId()
            );
        }
        try {
            return objectMapper.readValue(experiment.getParamsJson(), PARAMS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.ORIGINAL_EXPERIMENT_PARAMS_UNAVAILABLE,
                    "failed to parse original experiment params: " + experiment.getExperimentId()
            );
        }
    }

    private Map<String, Object> buildReplayParams(
            Map<String, Object> originalParams,
            Map<String, Object> appliedPatch
    ) {
        Map<String, Object> replayParams = new LinkedHashMap<>(originalParams);
        replayParams.putAll(appliedPatch);
        return replayParams;
    }
}
