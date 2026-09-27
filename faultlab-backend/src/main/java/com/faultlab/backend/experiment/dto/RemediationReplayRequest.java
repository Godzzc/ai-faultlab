package com.faultlab.backend.experiment.dto;

import java.util.Map;

public class RemediationReplayRequest {

    private String planId;
    private String scenarioCode;
    private Map<String, Object> parameterPatch;

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getScenarioCode() {
        return scenarioCode;
    }

    public void setScenarioCode(String scenarioCode) {
        this.scenarioCode = scenarioCode;
    }

    public Map<String, Object> getParameterPatch() {
        return parameterPatch;
    }

    public void setParameterPatch(Map<String, Object> parameterPatch) {
        this.parameterPatch = parameterPatch;
    }
}
