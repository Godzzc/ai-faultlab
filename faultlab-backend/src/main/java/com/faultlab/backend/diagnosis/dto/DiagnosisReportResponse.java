package com.faultlab.backend.diagnosis.dto;

import java.time.LocalDateTime;

public class DiagnosisReportResponse {

    private String experimentId;
    private String faultType;
    private String faultName;
    private String confidence;
    private String ruleResultJson;
    private String ragDocsJson;
    private String aiReportJson;
    private String fallbackReport;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(String experimentId) {
        this.experimentId = experimentId;
    }

    public String getFaultType() {
        return faultType;
    }

    public void setFaultType(String faultType) {
        this.faultType = faultType;
    }

    public String getFaultName() {
        return faultName;
    }

    public void setFaultName(String faultName) {
        this.faultName = faultName;
    }

    public String getConfidence() {
        return confidence;
    }

    public void setConfidence(String confidence) {
        this.confidence = confidence;
    }

    public String getRuleResultJson() {
        return ruleResultJson;
    }

    public void setRuleResultJson(String ruleResultJson) {
        this.ruleResultJson = ruleResultJson;
    }

    public String getRagDocsJson() {
        return ragDocsJson;
    }

    public void setRagDocsJson(String ragDocsJson) {
        this.ragDocsJson = ragDocsJson;
    }

    public String getAiReportJson() {
        return aiReportJson;
    }

    public void setAiReportJson(String aiReportJson) {
        this.aiReportJson = aiReportJson;
    }

    public String getFallbackReport() {
        return fallbackReport;
    }

    public void setFallbackReport(String fallbackReport) {
        this.fallbackReport = fallbackReport;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
