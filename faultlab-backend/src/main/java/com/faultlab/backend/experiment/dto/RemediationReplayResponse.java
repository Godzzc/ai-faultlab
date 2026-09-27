package com.faultlab.backend.experiment.dto;

import java.time.LocalDateTime;
import java.util.Map;

public class RemediationReplayResponse {

    private String planId;
    private String originalExperimentId;
    private String replayExperimentId;
    private String scenarioCode;
    private Map<String, Object> originalParams;
    private Map<String, Object> appliedPatch;
    private Map<String, Object> replayParams;
    private String status;
    private LocalDateTime createdAt;

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getOriginalExperimentId() {
        return originalExperimentId;
    }

    public void setOriginalExperimentId(String originalExperimentId) {
        this.originalExperimentId = originalExperimentId;
    }

    public String getReplayExperimentId() {
        return replayExperimentId;
    }

    public void setReplayExperimentId(String replayExperimentId) {
        this.replayExperimentId = replayExperimentId;
    }

    public String getScenarioCode() {
        return scenarioCode;
    }

    public void setScenarioCode(String scenarioCode) {
        this.scenarioCode = scenarioCode;
    }

    public Map<String, Object> getOriginalParams() {
        return originalParams;
    }

    public void setOriginalParams(Map<String, Object> originalParams) {
        this.originalParams = originalParams;
    }

    public Map<String, Object> getAppliedPatch() {
        return appliedPatch;
    }

    public void setAppliedPatch(Map<String, Object> appliedPatch) {
        this.appliedPatch = appliedPatch;
    }

    public Map<String, Object> getReplayParams() {
        return replayParams;
    }

    public void setReplayParams(Map<String, Object> replayParams) {
        this.replayParams = replayParams;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
