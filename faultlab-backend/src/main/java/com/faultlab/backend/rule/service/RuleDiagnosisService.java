package com.faultlab.backend.rule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.common.ErrorCode;
import com.faultlab.backend.diagnosis.entity.DiagnosisReport;
import com.faultlab.backend.diagnosis.mapper.DiagnosisReportMapper;
import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.experiment.mapper.FaultExperimentMapper;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.metric.mapper.FaultMetricMapper;
import com.faultlab.backend.rule.diagnoser.RuleDiagnoser;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.trace.annotation.TraceSpan;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RuleDiagnosisService {

    private final FaultExperimentMapper faultExperimentMapper;
    private final FaultMetricMapper faultMetricMapper;
    private final DiagnosisReportMapper diagnosisReportMapper;
    private final ObjectMapper objectMapper;
    private final List<RuleDiagnoser> diagnosers;

    public RuleDiagnosisService(
            FaultExperimentMapper faultExperimentMapper,
            FaultMetricMapper faultMetricMapper,
            DiagnosisReportMapper diagnosisReportMapper,
            ObjectMapper objectMapper,
            List<RuleDiagnoser> diagnosers
    ) {
        this.faultExperimentMapper = faultExperimentMapper;
        this.faultMetricMapper = faultMetricMapper;
        this.diagnosisReportMapper = diagnosisReportMapper;
        this.objectMapper = objectMapper;
        this.diagnosers = diagnosers;
    }

    @TraceSpan(operationName = "rule.diagnose", component = "RuleEngine")
    public RuleDiagnosisResult diagnose(String experimentId) {
        FaultExperiment experiment = faultExperimentMapper.selectOne(new LambdaQueryWrapper<FaultExperiment>()
                .eq(FaultExperiment::getExperimentId, experimentId)
                .last("LIMIT 1"));
        if (experiment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "experiment not found: " + experimentId);
        }

        RuleDiagnoser diagnoser = diagnosers.stream()
                .filter(candidate -> candidate.supports(experiment.getScenarioCode()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.PARAM_ERROR,
                        "unsupported scenarioCode: " + experiment.getScenarioCode()
                ));

        List<FaultMetric> metrics = faultMetricMapper.selectList(new LambdaQueryWrapper<FaultMetric>()
                .eq(FaultMetric::getExperimentId, experimentId));
        RuleDiagnosisResult result = diagnoser.diagnose(experiment, metrics);
        saveRuleDiagnosisResult(result);
        return result;
    }

    private void saveRuleDiagnosisResult(RuleDiagnosisResult result) {
        String ruleResultJson = serialize(result);
        DiagnosisReport existingReport = diagnosisReportMapper.selectOne(new LambdaQueryWrapper<DiagnosisReport>()
                .eq(DiagnosisReport::getExperimentId, result.getExperimentId())
                .last("LIMIT 1"));

        LocalDateTime now = LocalDateTime.now();
        if (existingReport == null) {
            DiagnosisReport report = new DiagnosisReport();
            report.setExperimentId(result.getExperimentId());
            report.setFaultType(result.getFaultType());
            report.setFaultName(result.getFaultName());
            report.setConfidence(BigDecimal.valueOf(result.getConfidence()));
            report.setRuleResultJson(ruleResultJson);
            report.setCreatedAt(now);
            report.setUpdatedAt(now);
            diagnosisReportMapper.insert(report);
            return;
        }

        existingReport.setFaultType(result.getFaultType());
        existingReport.setFaultName(result.getFaultName());
        existingReport.setConfidence(BigDecimal.valueOf(result.getConfidence()));
        existingReport.setRuleResultJson(ruleResultJson);
        existingReport.setUpdatedAt(now);
        diagnosisReportMapper.updateById(existingReport);
    }

    private String serialize(RuleDiagnosisResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "failed to serialize rule diagnosis result");
        }
    }
}
