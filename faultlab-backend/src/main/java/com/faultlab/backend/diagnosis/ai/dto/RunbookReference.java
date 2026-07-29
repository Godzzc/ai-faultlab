package com.faultlab.backend.diagnosis.ai.dto;

public record RunbookReference(
        String docId,
        String section,
        String title
) {
}
