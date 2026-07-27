package com.faultlab.backend.rule.diagnoser;

import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import java.util.List;

public interface RuleDiagnoser {

    boolean supports(String scenarioCode);

    RuleDiagnosisResult diagnose(FaultExperiment experiment, List<FaultMetric> metrics);
}
