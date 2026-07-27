package com.faultlab.backend.scenario;

import java.util.Map;

public interface FaultScenario {

    String scenarioCode();

    String scenarioName();

    void execute(String experimentId, Map<String, Object> params);
}
