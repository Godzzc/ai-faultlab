package com.faultlab.backend.diagnosis.ai.dto;

import java.time.LocalDateTime;

public record ExperimentInfo(
        String experimentId,
        String scenarioCode,
        String scenarioName,
        String status,
        String traceId,
        LocalDateTime startTime,
        LocalDateTime endTime
) {
}
