package com.faultlab.backend.diagnosis.ai.dto;

import java.util.List;

public record RemediationPlan(
        String planId,
        String experimentId,
        String scenarioCode,
        List<RemediationAction> actions,
        List<RemediationExpectedMetricEffect> expectedEffects,
        List<String> evidence,
        Double confidence,
        String status,
        List<String> warnings
) {
}
