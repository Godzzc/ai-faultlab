package com.faultlab.backend.experiment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.common.ErrorCode;
import com.faultlab.backend.diagnosis.dto.DiagnosisReportResponse;
import com.faultlab.backend.diagnosis.entity.DiagnosisReport;
import com.faultlab.backend.diagnosis.mapper.DiagnosisReportMapper;
import com.faultlab.backend.experiment.dto.ExperimentDetailResponse;
import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.experiment.mapper.FaultExperimentMapper;
import com.faultlab.backend.metric.dto.MetricResponse;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.metric.mapper.FaultMetricMapper;
import com.faultlab.backend.trace.annotation.TraceSpan;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ExperimentQueryService {

    private final FaultExperimentMapper faultExperimentMapper;
    private final FaultMetricMapper faultMetricMapper;
    private final DiagnosisReportMapper diagnosisReportMapper;

    public ExperimentQueryService(
            FaultExperimentMapper faultExperimentMapper,
            FaultMetricMapper faultMetricMapper,
            DiagnosisReportMapper diagnosisReportMapper
    ) {
        this.faultExperimentMapper = faultExperimentMapper;
        this.faultMetricMapper = faultMetricMapper;
        this.diagnosisReportMapper = diagnosisReportMapper;
    }

    @TraceSpan(operationName = "experiment.query.detail", component = "MySQL")
    public ExperimentDetailResponse getExperimentDetail(String experimentId) {
        return toExperimentDetailResponse(findExperimentOrThrow(experimentId));
    }

    @TraceSpan(operationName = "experiment.query.metrics", component = "MySQL")
    public List<MetricResponse> getExperimentMetrics(String experimentId) {
        findExperimentOrThrow(experimentId);
        return faultMetricMapper.selectList(new LambdaQueryWrapper<FaultMetric>()
                        .eq(FaultMetric::getExperimentId, experimentId)
                        .orderByAsc(FaultMetric::getCreatedAt))
                .stream()
                .map(this::toMetricResponse)
                .toList();
    }

    @TraceSpan(operationName = "diagnosis.query.report", component = "MySQL")
    public DiagnosisReportResponse getDiagnosisReport(String experimentId) {
        findExperimentOrThrow(experimentId);
        DiagnosisReport report = diagnosisReportMapper.selectOne(new LambdaQueryWrapper<DiagnosisReport>()
                .eq(DiagnosisReport::getExperimentId, experimentId)
                .last("LIMIT 1"));
        if (report == null) {
            return null;
        }
        return toDiagnosisReportResponse(report);
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

    private ExperimentDetailResponse toExperimentDetailResponse(FaultExperiment experiment) {
        ExperimentDetailResponse response = new ExperimentDetailResponse();
        response.setExperimentId(experiment.getExperimentId());
        response.setScenarioCode(experiment.getScenarioCode());
        response.setStatus(experiment.getStatus());
        response.setTraceId(experiment.getTraceId());
        response.setStartTime(experiment.getStartTime());
        response.setEndTime(experiment.getEndTime());
        response.setCreatedAt(experiment.getCreatedAt());
        response.setUpdatedAt(experiment.getUpdatedAt());
        return response;
    }

    private MetricResponse toMetricResponse(FaultMetric metric) {
        MetricResponse response = new MetricResponse();
        response.setExperimentId(metric.getExperimentId());
        response.setMetricName(metric.getMetricName());
        response.setMetricValue(toPlainString(metric.getMetricValue()));
        response.setMetricUnit(metric.getMetricUnit());
        response.setComponent(metric.getComponent());
        response.setCreatedAt(metric.getCreatedAt());
        return response;
    }

    private DiagnosisReportResponse toDiagnosisReportResponse(DiagnosisReport report) {
        DiagnosisReportResponse response = new DiagnosisReportResponse();
        response.setExperimentId(report.getExperimentId());
        response.setFaultType(report.getFaultType());
        response.setFaultName(report.getFaultName());
        response.setConfidence(toPlainString(report.getConfidence()));
        response.setRuleResultJson(report.getRuleResultJson());
        response.setRagDocsJson(report.getRagDocsJson());
        response.setAiReportJson(report.getAiReportJson());
        response.setFallbackReport(report.getFallbackReport());
        response.setCreatedAt(report.getCreatedAt());
        response.setUpdatedAt(report.getUpdatedAt());
        return response;
    }

    private String toPlainString(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
