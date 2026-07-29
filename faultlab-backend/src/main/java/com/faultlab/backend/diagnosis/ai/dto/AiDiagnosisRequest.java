package com.faultlab.backend.diagnosis.ai.dto;

import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.trace.dto.TraceTreeResponse;
import java.util.List;

public record AiDiagnosisRequest(
        ExperimentInfo experiment,
        List<MetricItem> metrics,
        TraceTreeResponse traceTree,
        RuleDiagnosisResult ruleResult
) {
}
