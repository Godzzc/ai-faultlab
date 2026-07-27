package com.faultlab.backend.experiment.controller;

import com.faultlab.backend.common.ApiResponse;
import com.faultlab.backend.experiment.dto.StartExperimentRequest;
import com.faultlab.backend.experiment.dto.StartExperimentResponse;
import com.faultlab.backend.experiment.service.ExperimentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    private final ExperimentService experimentService;

    public ExperimentController(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @PostMapping("/start")
    public ApiResponse<StartExperimentResponse> startExperiment(@RequestBody StartExperimentRequest request) {
        return ApiResponse.success(experimentService.startExperiment(request));
    }
}
