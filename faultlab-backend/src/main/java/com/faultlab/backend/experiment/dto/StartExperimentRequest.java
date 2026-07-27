package com.faultlab.backend.experiment.dto;

import java.util.Map;

public class StartExperimentRequest {

    private String scenarioCode;
    private Map<String, Object> params;

    public String getScenarioCode() {
        return scenarioCode;
    }

    public void setScenarioCode(String scenarioCode) {
        this.scenarioCode = scenarioCode;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }
}
