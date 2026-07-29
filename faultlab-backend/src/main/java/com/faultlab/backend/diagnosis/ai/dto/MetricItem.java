package com.faultlab.backend.diagnosis.ai.dto;

import java.time.LocalDateTime;

public record MetricItem(
        String metricName,
        String metricValue,
        String metricUnit,
        String component,
        LocalDateTime createdAt
) {
}
