package com.faultlab.backend.diagnosis.ai.dto;

import java.util.Map;

public record RemediationAction(
        String actionType,
        String description,
        Map<String, Object> parameterPatch,
        String rationale
) {
}
