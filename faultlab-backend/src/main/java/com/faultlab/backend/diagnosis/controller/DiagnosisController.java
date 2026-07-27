package com.faultlab.backend.diagnosis.controller;

import com.faultlab.backend.common.ApiResponse;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.rule.service.RuleDiagnosisService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/diagnosis")
public class DiagnosisController {

    private final RuleDiagnosisService ruleDiagnosisService;

    public DiagnosisController(RuleDiagnosisService ruleDiagnosisService) {
        this.ruleDiagnosisService = ruleDiagnosisService;
    }

    @PostMapping("/{experimentId}/rule")
    public ApiResponse<RuleDiagnosisResult> diagnoseByRule(@PathVariable String experimentId) {
        return ApiResponse.success(ruleDiagnosisService.diagnose(experimentId));
    }
}
