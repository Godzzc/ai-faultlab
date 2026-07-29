package com.faultlab.backend.diagnosis.controller;

import com.faultlab.backend.common.ApiResponse;
import com.faultlab.backend.diagnosis.ai.AiDiagnosisService;
import com.faultlab.backend.diagnosis.ai.dto.AiDiagnosisResponse;
import com.faultlab.backend.diagnosis.dto.DiagnosisReportResponse;
import com.faultlab.backend.experiment.service.ExperimentQueryService;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.rule.service.RuleDiagnosisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/diagnosis")
public class DiagnosisController {

    private final RuleDiagnosisService ruleDiagnosisService;
    private final ExperimentQueryService experimentQueryService;
    private final AiDiagnosisService aiDiagnosisService;

    public DiagnosisController(
            RuleDiagnosisService ruleDiagnosisService,
            ExperimentQueryService experimentQueryService,
            AiDiagnosisService aiDiagnosisService
    ) {
        this.ruleDiagnosisService = ruleDiagnosisService;
        this.experimentQueryService = experimentQueryService;
        this.aiDiagnosisService = aiDiagnosisService;
    }

    @PostMapping("/{experimentId}/rule")
    public ApiResponse<RuleDiagnosisResult> diagnoseByRule(@PathVariable String experimentId) {
        return ApiResponse.success(ruleDiagnosisService.diagnose(experimentId));
    }

    @GetMapping("/{experimentId}")
    public ApiResponse<DiagnosisReportResponse> getDiagnosisReport(@PathVariable String experimentId) {
        return ApiResponse.success(experimentQueryService.getDiagnosisReport(experimentId));
    }

    @PostMapping("/{experimentId}/ai/generate")
    public ApiResponse<AiDiagnosisResponse> generateAiDiagnosis(@PathVariable String experimentId) {
        return ApiResponse.success(aiDiagnosisService.generateAiDiagnosis(experimentId));
    }
}
