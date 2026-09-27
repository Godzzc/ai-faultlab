package com.faultlab.backend.experiment.controller;

import com.faultlab.backend.common.ApiResponse;
import com.faultlab.backend.experiment.dto.ExperimentDetailResponse;
import com.faultlab.backend.experiment.dto.RemediationReplayRequest;
import com.faultlab.backend.experiment.dto.RemediationReplayResponse;
import com.faultlab.backend.experiment.dto.StartExperimentRequest;
import com.faultlab.backend.experiment.dto.StartExperimentResponse;
import com.faultlab.backend.experiment.remediation.RemediationReplayService;
import com.faultlab.backend.experiment.service.ExperimentQueryService;
import com.faultlab.backend.experiment.service.ExperimentService;
import com.faultlab.backend.metric.dto.MetricResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentService experimentService;
    private final ExperimentQueryService experimentQueryService;
    private final RemediationReplayService remediationReplayService;

    public ExperimentController(
            ExperimentService experimentService,
            ExperimentQueryService experimentQueryService,
            RemediationReplayService remediationReplayService
    ) {
        this.experimentService = experimentService;
        this.experimentQueryService = experimentQueryService;
        this.remediationReplayService = remediationReplayService;
    }

    @PostMapping("/start")
    public ApiResponse<StartExperimentResponse> startExperiment(@RequestBody StartExperimentRequest request) {
        return ApiResponse.success(experimentService.startExperiment(request));
    }

    @PostMapping("/{experimentId}/remediation-replay")
    public ApiResponse<RemediationReplayResponse> replayRemediation(
            @PathVariable String experimentId,
            @RequestBody RemediationReplayRequest request
    ) {
        return ApiResponse.success(remediationReplayService.replay(experimentId, request));
    }

    @GetMapping("/{experimentId}")
    public ApiResponse<ExperimentDetailResponse> getExperimentDetail(@PathVariable String experimentId) {
        return ApiResponse.success(experimentQueryService.getExperimentDetail(experimentId));
    }

    @GetMapping("/{experimentId}/metrics")
    public ApiResponse<List<MetricResponse>> getExperimentMetrics(@PathVariable String experimentId) {
        return ApiResponse.success(experimentQueryService.getExperimentMetrics(experimentId));
    }
}
