package com.faultlab.backend.diagnosis.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.common.ErrorCode;
import com.faultlab.backend.diagnosis.ai.dto.AiDiagnosisRequest;
import com.faultlab.backend.diagnosis.ai.dto.ExperimentInfo;
import com.faultlab.backend.diagnosis.ai.dto.MetricItem;
import com.faultlab.backend.diagnosis.entity.DiagnosisReport;
import com.faultlab.backend.diagnosis.mapper.DiagnosisReportMapper;
import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.experiment.mapper.FaultExperimentMapper;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.metric.mapper.FaultMetricMapper;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.rule.service.RuleDiagnosisService;
import com.faultlab.backend.trace.annotation.TraceSpan;
import com.faultlab.backend.trace.dto.TraceTreeResponse;
import com.faultlab.backend.trace.service.TraceQueryService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class EvidencePackageBuilder {

    private static final Logger log = LoggerFactory.getLogger(EvidencePackageBuilder.class);

    private final FaultExperimentMapper faultExperimentMapper;
    private final FaultMetricMapper faultMetricMapper;
    private final DiagnosisReportMapper diagnosisReportMapper;
    private final TraceQueryService traceQueryService;
    private final RuleDiagnosisService ruleDiagnosisService;
    private final ObjectMapper objectMapper;

    public EvidencePackageBuilder(
            FaultExperimentMapper faultExperimentMapper,
            FaultMetricMapper faultMetricMapper,
            DiagnosisReportMapper diagnosisReportMapper,
            TraceQueryService traceQueryService,
            RuleDiagnosisService ruleDiagnosisService,
            ObjectMapper objectMapper
    ) {
        this.faultExperimentMapper = faultExperimentMapper;
        this.faultMetricMapper = faultMetricMapper;
        this.diagnosisReportMapper = diagnosisReportMapper;
        this.traceQueryService = traceQueryService;
        this.ruleDiagnosisService = ruleDiagnosisService;
        this.objectMapper = objectMapper;
    }

    @TraceSpan(operationName = "ai.evidence.build", component = "AI")
    public AiDiagnosisRequest build(String experimentId) {
        FaultExperiment experiment = findExperimentOrThrow(experimentId);
        List<MetricItem> metrics = loadMetrics(experimentId);
        TraceTreeResponse traceTree = traceQueryService.getTraceTree(experiment.getTraceId());
        RuleDiagnosisResult ruleResult = loadRuleResult(experimentId);
        return new AiDiagnosisRequest(toExperimentInfo(experiment), metrics, traceTree, ruleResult);
    }

    private FaultExperiment findExperimentOrThrow(String experimentId) {
        if (!StringUtils.hasText(experimentId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "experimentId is required");
        }
        FaultExperiment experiment = faultExperimentMapper.selectOne(new LambdaQueryWrapper<FaultExperiment>()
                .eq(FaultExperiment::getExperimentId, experimentId)
                .last("LIMIT 1"));
        if (experiment == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "experiment not found: " + experimentId);
        }
        return experiment;
    }

    private List<MetricItem> loadMetrics(String experimentId) {
        return faultMetricMapper.selectList(new LambdaQueryWrapper<FaultMetric>()
                        .eq(FaultMetric::getExperimentId, experimentId)
                        .orderByAsc(FaultMetric::getCreatedAt))
                .stream()
                .map(this::toMetricItem)
                .toList();
    }

    private RuleDiagnosisResult loadRuleResult(String experimentId) {
        DiagnosisReport report = diagnosisReportMapper.selectOne(new LambdaQueryWrapper<DiagnosisReport>()
                .eq(DiagnosisReport::getExperimentId, experimentId)
                .last("LIMIT 1"));
        if (report == null || !StringUtils.hasText(report.getRuleResultJson())) {
            return ruleDiagnosisService.diagnose(experimentId);
        }
        try {
            return objectMapper.readValue(report.getRuleResultJson(), RuleDiagnosisResult.class);
        } catch (JsonProcessingException exception) {
            log.warn("Failed to parse rule_result_json for experiment {}: {}", experimentId, exception.getMessage());
            return ruleDiagnosisService.diagnose(experimentId);
        }
    }

    private ExperimentInfo toExperimentInfo(FaultExperiment experiment) {
        return new ExperimentInfo(
                experiment.getExperimentId(),
                experiment.getScenarioCode(),
                experiment.getScenarioName(),
                experiment.getStatus(),
                experiment.getTraceId(),
                experiment.getStartTime(),
                experiment.getEndTime()
        );
    }

    private MetricItem toMetricItem(FaultMetric metric) {
        return new MetricItem(
                metric.getMetricName(),
                metric.getMetricValue() == null ? null : metric.getMetricValue().stripTrailingZeros().toPlainString(),
                metric.getMetricUnit(),
                metric.getComponent(),
                metric.getCreatedAt()
        );
    }
}
