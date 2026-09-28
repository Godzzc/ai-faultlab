package com.faultlab.backend.experiment.remediation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.common.ErrorCode;
import com.faultlab.backend.experiment.dto.ExpectedMetricEffect;
import com.faultlab.backend.experiment.dto.MetricComparison;
import com.faultlab.backend.experiment.dto.MetricComparisonStatus;
import com.faultlab.backend.experiment.dto.RemediationValidationReport;
import com.faultlab.backend.experiment.dto.RemediationValidationStatus;
import com.faultlab.backend.experiment.dto.TraceComparison;
import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.experiment.mapper.FaultExperimentMapper;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.metric.mapper.FaultMetricMapper;
import com.faultlab.backend.trace.entity.TraceSpan;
import com.faultlab.backend.trace.manager.TraceManager;
import com.faultlab.backend.trace.mapper.TraceSpanMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RemediationValidationService {

    private static final TypeReference<LinkedHashMap<String, Object>> PARAMS_TYPE = new TypeReference<>() {
    };

    private final FaultExperimentMapper faultExperimentMapper;
    private final FaultMetricMapper faultMetricMapper;
    private final TraceSpanMapper traceSpanMapper;
    private final ObjectMapper objectMapper;
    private final RemediationReplayPolicy remediationReplayPolicy;
    private final RemediationValidationPolicy remediationValidationPolicy;
    private final RemediationMetricComparator metricComparator;

    public RemediationValidationService(
            FaultExperimentMapper faultExperimentMapper,
            FaultMetricMapper faultMetricMapper,
            TraceSpanMapper traceSpanMapper,
            ObjectMapper objectMapper,
            RemediationReplayPolicy remediationReplayPolicy,
            RemediationValidationPolicy remediationValidationPolicy,
            RemediationMetricComparator metricComparator
    ) {
        this.faultExperimentMapper = faultExperimentMapper;
        this.faultMetricMapper = faultMetricMapper;
        this.traceSpanMapper = traceSpanMapper;
        this.objectMapper = objectMapper;
        this.remediationReplayPolicy = remediationReplayPolicy;
        this.remediationValidationPolicy = remediationValidationPolicy;
        this.metricComparator = metricComparator;
    }

    public RemediationValidationReport validate(String replayExperimentId) {
        if (!StringUtils.hasText(replayExperimentId)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "replay experimentId is required");
        }

        FaultExperiment replayExperiment = findReplayExperiment(replayExperimentId);
        if (!StringUtils.hasText(replayExperiment.getSourceExperimentId())) {
            throw new BusinessException(
                    ErrorCode.REMEDIATION_VALIDATION_SOURCE_MISSING,
                    "replay experiment sourceExperimentId is missing: " + replayExperimentId
            );
        }

        FaultExperiment originalExperiment = findOriginalExperiment(replayExperiment.getSourceExperimentId());
        String scenarioCode = replayExperiment.getScenarioCode();
        if (!Objects.equals(originalExperiment.getScenarioCode(), scenarioCode)) {
            throw new BusinessException(
                    ErrorCode.SCENARIO_MISMATCH,
                    "original and replay scenarioCode do not match"
            );
        }
        if (!remediationValidationPolicy.supports(scenarioCode)) {
            throw new BusinessException(
                    ErrorCode.REMEDIATION_VALIDATION_UNSUPPORTED_SCENARIO,
                    "remediation validation unsupported for scenario: " + scenarioCode
            );
        }

        Map<String, Object> originalParams = readParams(originalExperiment);
        Map<String, Object> replayParams = readParams(replayExperiment);
        Map<String, Object> appliedPatch = deriveAppliedPatch(originalParams, replayParams);
        verifyCounterfactualInvariant(scenarioCode, appliedPatch);

        List<ExpectedMetricEffect> expectedEffects = remediationValidationPolicy.resolveExpectedEffects(
                scenarioCode,
                originalParams,
                replayParams,
                appliedPatch
        );
        Map<String, BigDecimal> originalMetrics = loadMetricMap(originalExperiment.getExperimentId());
        Map<String, BigDecimal> replayMetrics = loadMetricMap(replayExperiment.getExperimentId());
        List<MetricComparison> metricComparisons = compareMetrics(expectedEffects, originalMetrics, replayMetrics);
        TraceComparison traceComparison = compareTrace(originalExperiment, replayExperiment);
        RemediationValidationStatus status = calculateStatus(expectedEffects, metricComparisons);

        RemediationValidationReport report = new RemediationValidationReport();
        report.setOriginalExperimentId(originalExperiment.getExperimentId());
        report.setReplayExperimentId(replayExperiment.getExperimentId());
        report.setScenarioCode(scenarioCode);
        report.setStatus(status);
        report.setAppliedPatch(appliedPatch);
        report.setMetricComparisons(metricComparisons);
        report.setTraceComparison(traceComparison);
        report.setTotalExpectedEffects(expectedEffects.size());
        report.setMatchedEffects(countByStatus(metricComparisons, MetricComparisonStatus.MATCHED));
        report.setUnmatchedEffects(countByStatus(metricComparisons, MetricComparisonStatus.UNMATCHED));
        report.setInconclusiveEffects(countByStatus(metricComparisons, MetricComparisonStatus.INCONCLUSIVE));
        report.setSummary(summary(report));
        report.setValidatedAt(LocalDateTime.now());
        return report;
    }

    private FaultExperiment findReplayExperiment(String replayExperimentId) {
        FaultExperiment experiment = findExperiment(replayExperimentId);
        if (experiment == null) {
            throw new BusinessException(
                    ErrorCode.REMEDIATION_VALIDATION_REPLAY_NOT_FOUND,
                    "replay experiment not found: " + replayExperimentId
            );
        }
        return experiment;
    }

    private FaultExperiment findOriginalExperiment(String originalExperimentId) {
        FaultExperiment experiment = findExperiment(originalExperimentId);
        if (experiment == null) {
            throw new BusinessException(
                    ErrorCode.REMEDIATION_VALIDATION_ORIGINAL_NOT_FOUND,
                    "original experiment not found: " + originalExperimentId
            );
        }
        return experiment;
    }

    private FaultExperiment findExperiment(String experimentId) {
        return faultExperimentMapper.selectOne(new LambdaQueryWrapper<FaultExperiment>()
                .eq(FaultExperiment::getExperimentId, experimentId)
                .last("LIMIT 1"));
    }

    private Map<String, Object> readParams(FaultExperiment experiment) {
        if (!StringUtils.hasText(experiment.getParamsJson())) {
            throw new BusinessException(
                    ErrorCode.REMEDIATION_VALIDATION_PARAMS_UNAVAILABLE,
                    "experiment params unavailable: " + experiment.getExperimentId()
            );
        }
        try {
            return objectMapper.readValue(experiment.getParamsJson(), PARAMS_TYPE);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.REMEDIATION_VALIDATION_PARAMS_UNAVAILABLE,
                    "failed to parse experiment params: " + experiment.getExperimentId()
            );
        }
    }

    private Map<String, Object> deriveAppliedPatch(
            Map<String, Object> originalParams,
            Map<String, Object> replayParams
    ) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(originalParams.keySet());
        keys.addAll(replayParams.keySet());

        Map<String, Object> appliedPatch = new LinkedHashMap<>();
        for (String key : keys) {
            Object originalValue = originalParams.get(key);
            Object replayValue = replayParams.get(key);
            if (!valuesEqual(originalValue, replayValue)) {
                appliedPatch.put(key, replayValue);
            }
        }
        return appliedPatch;
    }

    private void verifyCounterfactualInvariant(String scenarioCode, Map<String, Object> appliedPatch) {
        if (appliedPatch.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> normalized = remediationReplayPolicy.validateAndNormalize(scenarioCode, appliedPatch);
            if (!patchValuesEqual(normalized, appliedPatch)) {
                throw invariantViolation("replay params are not equivalent to normalized remediation patch");
            }
        } catch (BusinessException exception) {
            if (exception.getErrorCode() == ErrorCode.REMEDIATION_REPLAY_UNSUPPORTED_SCENARIO) {
                throw new BusinessException(
                        ErrorCode.REMEDIATION_VALIDATION_UNSUPPORTED_SCENARIO,
                        exception.getMessage()
                );
            }
            throw invariantViolation(exception.getMessage());
        }
    }

    private boolean patchValuesEqual(Map<String, Object> normalized, Map<String, Object> appliedPatch) {
        if (!normalized.keySet().equals(appliedPatch.keySet())) {
            return false;
        }
        for (String key : normalized.keySet()) {
            if (!valuesEqual(normalized.get(key), appliedPatch.get(key))) {
                return false;
            }
        }
        return true;
    }

    private BusinessException invariantViolation(String message) {
        return new BusinessException(ErrorCode.COUNTERFACTUAL_INVARIANT_VIOLATION, message);
    }

    private boolean valuesEqual(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return BigDecimal.valueOf(leftNumber.doubleValue()).compareTo(BigDecimal.valueOf(rightNumber.doubleValue())) == 0;
        }
        return Objects.equals(left, right);
    }

    private Map<String, BigDecimal> loadMetricMap(String experimentId) {
        List<FaultMetric> metrics = faultMetricMapper.selectList(new LambdaQueryWrapper<FaultMetric>()
                .eq(FaultMetric::getExperimentId, experimentId)
                .orderByAsc(FaultMetric::getCreatedAt));
        Map<String, BigDecimal> metricMap = new LinkedHashMap<>();
        for (FaultMetric metric : metrics) {
            if (StringUtils.hasText(metric.getMetricName())) {
                metricMap.put(metric.getMetricName(), metric.getMetricValue());
            }
        }
        return metricMap;
    }

    private List<MetricComparison> compareMetrics(
            List<ExpectedMetricEffect> expectedEffects,
            Map<String, BigDecimal> originalMetrics,
            Map<String, BigDecimal> replayMetrics
    ) {
        List<MetricComparison> comparisons = new ArrayList<>();
        for (ExpectedMetricEffect expectedEffect : expectedEffects) {
            comparisons.add(metricComparator.compare(expectedEffect, originalMetrics, replayMetrics));
        }
        return comparisons;
    }

    private TraceComparison compareTrace(FaultExperiment originalExperiment, FaultExperiment replayExperiment) {
        List<TraceSpan> beforeSpans = loadTraceSpans(originalExperiment.getTraceId());
        List<TraceSpan> afterSpans = loadTraceSpans(replayExperiment.getTraceId());

        TraceComparison comparison = new TraceComparison();
        comparison.setBeforeSpanCount(beforeSpans.size());
        comparison.setAfterSpanCount(afterSpans.size());
        comparison.setBeforeErrorSpanCount(errorSpanCount(beforeSpans));
        comparison.setAfterErrorSpanCount(errorSpanCount(afterSpans));
        comparison.setBeforeTotalDurationMs(totalDurationMs(beforeSpans));
        comparison.setAfterTotalDurationMs(totalDurationMs(afterSpans));
        comparison.setBeforeOperationCounts(operationCounts(beforeSpans));
        comparison.setAfterOperationCounts(operationCounts(afterSpans));
        return comparison;
    }

    private List<TraceSpan> loadTraceSpans(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return List.of();
        }
        return traceSpanMapper.selectList(new LambdaQueryWrapper<TraceSpan>()
                .eq(TraceSpan::getTraceId, traceId)
                .orderByAsc(TraceSpan::getStartTime)
                .orderByAsc(TraceSpan::getCreatedAt));
    }

    private int errorSpanCount(List<TraceSpan> spans) {
        return (int) spans.stream()
                .filter(span -> TraceManager.STATUS_ERROR.equals(span.getStatus()))
                .count();
    }

    private long totalDurationMs(List<TraceSpan> spans) {
        return spans.stream()
                .map(TraceSpan::getDurationMs)
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
    }

    private Map<String, Long> operationCounts(List<TraceSpan> spans) {
        return spans.stream()
                .filter(span -> StringUtils.hasText(span.getOperationName()))
                .sorted(Comparator.comparing(TraceSpan::getOperationName))
                .collect(Collectors.groupingBy(
                        TraceSpan::getOperationName,
                        LinkedHashMap::new,
                        Collectors.counting()
                ));
    }

    private RemediationValidationStatus calculateStatus(
            List<ExpectedMetricEffect> expectedEffects,
            List<MetricComparison> comparisons
    ) {
        if (expectedEffects.isEmpty() || comparisons.isEmpty()) {
            return RemediationValidationStatus.INCONCLUSIVE;
        }
        int inconclusive = countByStatus(comparisons, MetricComparisonStatus.INCONCLUSIVE);
        if (inconclusive > 0) {
            return RemediationValidationStatus.INCONCLUSIVE;
        }
        int matched = countByStatus(comparisons, MetricComparisonStatus.MATCHED);
        int unmatched = countByStatus(comparisons, MetricComparisonStatus.UNMATCHED);
        if (matched == comparisons.size()) {
            return RemediationValidationStatus.VERIFIED;
        }
        if (matched > 0 && unmatched > 0) {
            return RemediationValidationStatus.PARTIALLY_VERIFIED;
        }
        return RemediationValidationStatus.NOT_VERIFIED;
    }

    private int countByStatus(List<MetricComparison> comparisons, MetricComparisonStatus status) {
        return (int) comparisons.stream()
                .filter(comparison -> comparison.getStatus() == status)
                .count();
    }

    private String summary(RemediationValidationReport report) {
        if (report.getTotalExpectedEffects() == 0) {
            return "0 of 0 expected metric effects matched.";
        }
        return report.getMatchedEffects()
                + " of "
                + report.getTotalExpectedEffects()
                + " expected metric effects matched.";
    }
}
