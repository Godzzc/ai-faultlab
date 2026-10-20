package com.faultlab.backend.diagnosis.ai.dto;

public record RemediationExpectedMetricEffect(
        String metricName,
        String direction,
        String description
) {
}
