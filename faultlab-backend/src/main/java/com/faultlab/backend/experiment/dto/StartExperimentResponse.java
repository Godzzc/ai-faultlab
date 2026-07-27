package com.faultlab.backend.experiment.dto;

public class StartExperimentResponse {

    private String experimentId;
    private String traceId;
    private String status;

    public StartExperimentResponse(String experimentId, String traceId, String status) {
        this.experimentId = experimentId;
        this.traceId = traceId;
        this.status = status;
    }

    public String getExperimentId() {
        return experimentId;
    }

    public void setExperimentId(String experimentId) {
        this.experimentId = experimentId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
