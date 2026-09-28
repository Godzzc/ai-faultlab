package com.faultlab.backend.experiment.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class RemediationValidationReport {

    private String originalExperimentId;
    private String replayExperimentId;
    private String scenarioCode;
    private RemediationValidationStatus status;
    private Map<String, Object> appliedPatch;
    private int totalExpectedEffects;
    private int matchedEffects;
    private int unmatchedEffects;
    private int inconclusiveEffects;
    private List<MetricComparison> metricComparisons;
    private TraceComparison traceComparison;
    private String summary;
    private LocalDateTime validatedAt;

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

    public RemediationValidationStatus getStatus() {
        return status;
    }

    public void setStatus(RemediationValidationStatus status) {
        this.status = status;
    }

    public Map<String, Object> getAppliedPatch() {
        return appliedPatch;
    }

    public void setAppliedPatch(Map<String, Object> appliedPatch) {
        this.appliedPatch = appliedPatch;
    }

    public int getTotalExpectedEffects() {
        return totalExpectedEffects;
    }

    public void setTotalExpectedEffects(int totalExpectedEffects) {
        this.totalExpectedEffects = totalExpectedEffects;
    }

    public int getMatchedEffects() {
        return matchedEffects;
    }

    public void setMatchedEffects(int matchedEffects) {
        this.matchedEffects = matchedEffects;
    }

    public int getUnmatchedEffects() {
        return unmatchedEffects;
    }

    public void setUnmatchedEffects(int unmatchedEffects) {
        this.unmatchedEffects = unmatchedEffects;
    }

    public int getInconclusiveEffects() {
        return inconclusiveEffects;
    }

    public void setInconclusiveEffects(int inconclusiveEffects) {
        this.inconclusiveEffects = inconclusiveEffects;
    }

    public List<MetricComparison> getMetricComparisons() {
        return metricComparisons;
    }

    public void setMetricComparisons(List<MetricComparison> metricComparisons) {
        this.metricComparisons = metricComparisons;
    }

    public TraceComparison getTraceComparison() {
        return traceComparison;
    }

    public void setTraceComparison(TraceComparison traceComparison) {
        this.traceComparison = traceComparison;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public LocalDateTime getValidatedAt() {
        return validatedAt;
    }

    public void setValidatedAt(LocalDateTime validatedAt) {
        this.validatedAt = validatedAt;
    }
}
