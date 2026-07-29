package com.faultlab.backend.diagnosis.ai.dto;

import java.util.List;

public record AiDiagnosisResponse(
        String experimentId,
        String faultType,
        String faultName,
        Double confidence,
        String summary,
        List<String> phenomenon,
        List<String> evidence,
        List<String> rootCauses,
        List<String> suggestions,
        List<RunbookReference> runbookReferences,
        Boolean fallback
) {
}
