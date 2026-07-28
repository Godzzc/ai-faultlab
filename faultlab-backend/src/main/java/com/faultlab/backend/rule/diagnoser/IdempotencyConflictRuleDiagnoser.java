package com.faultlab.backend.rule.diagnoser;

import com.faultlab.backend.experiment.entity.FaultExperiment;
import com.faultlab.backend.metric.entity.FaultMetric;
import com.faultlab.backend.rule.dto.RuleDiagnosisResult;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyConflictRuleDiagnoser implements RuleDiagnoser {

    private static final String FAULT_NAME = "幂等冲突";
    private static final List<String> DEFAULT_SUGGESTIONS = List.of(
            "接口层要求客户端传递稳定的 Idempotency-Key",
            "服务端保存 requestHash，避免同一幂等 key 携带不同请求体",
            "Redis 作为快路径拦截重复请求，数据库唯一索引作为最终兜底",
            "对 PROCESSING / SUCCESS / FAILED 状态做明确区分",
            "对处理中请求返回处理中或历史结果，避免重复执行业务逻辑",
            "Redis 异常时根据业务风险选择 fail-open 或 fail-close"
    );

    @Override
    public boolean supports(String scenarioCode) {
        return ScenarioCode.IDEMPOTENCY_CONFLICT.equals(scenarioCode);
    }

    @Override
    public RuleDiagnosisResult diagnose(FaultExperiment experiment, List<FaultMetric> metrics) {
        Map<String, BigDecimal> metricValues = metrics == null ? Map.of() : metrics.stream()
                .filter(metric -> metric.getMetricName() != null && metric.getMetricValue() != null)
                .collect(Collectors.toMap(
                        FaultMetric::getMetricName,
                        FaultMetric::getMetricValue,
                        (left, right) -> right
                ));

        Optional<BigDecimal> requestCount = metric(metricValues, "requestCount");
        Optional<BigDecimal> duplicateCount = metric(metricValues, "duplicateCount");
        Optional<BigDecimal> conflictCount = metric(metricValues, "conflictCount");
        Optional<BigDecimal> acceptedCount = metric(metricValues, "acceptedCount");
        Optional<BigDecimal> rejectedDuplicateCount = metric(metricValues, "rejectedDuplicateCount");
        Optional<BigDecimal> hashMismatchCount = metric(metricValues, "hashMismatchCount");
        Optional<BigDecimal> redisSetNxSuccessCount = metric(metricValues, "redisSetNxSuccessCount");
        Optional<BigDecimal> redisSetNxFailCount = metric(metricValues, "redisSetNxFailCount");
        Optional<BigDecimal> redisErrorCount = metric(metricValues, "redisErrorCount");
        Optional<BigDecimal> avgCheckDurationMs = metric(metricValues, "avgCheckDurationMs");

        RuleDiagnosisResult result = baseResult(experiment);
        List<String> evidence = buildEvidence(
                requestCount,
                duplicateCount,
                conflictCount,
                acceptedCount,
                rejectedDuplicateCount,
                hashMismatchCount,
                redisSetNxSuccessCount,
                redisSetNxFailCount,
                redisErrorCount,
                avgCheckDurationMs
        );

        if (requestCount.isEmpty() || (duplicateCount.isEmpty() && rejectedDuplicateCount.isEmpty())
                || (conflictCount.isEmpty() && hashMismatchCount.isEmpty()) || redisErrorCount.isEmpty()) {
            result.setMatched(false);
            result.setConfidence(0.20);
            result.setReason("关键幂等指标不足，无法确认是否存在重复提交或幂等冲突。");
            result.setEvidence(evidence);
            return result;
        }

        boolean hashMismatch = greaterThanZero(hashMismatchCount) || greaterThanZero(conflictCount);
        boolean duplicate = greaterThanZero(rejectedDuplicateCount) || greaterThanZero(duplicateCount);
        boolean redisSetNxFailed = greaterThanZero(redisSetNxFailCount);
        boolean redisError = greaterThanZero(redisErrorCount);

        if (greaterThanZero(hashMismatchCount) && greaterThanZero(duplicateCount)) {
            result.setMatched(true);
            result.setConfidence(0.90);
            result.setReason("同一个幂等 key 同时出现不同请求体和重复提交，疑似幂等冲突。");
        } else if (hashMismatch) {
            result.setMatched(true);
            result.setConfidence(0.80);
            result.setReason("同一个幂等 key 出现不同 requestHash，存在参数冲突风险。");
        } else if (duplicate) {
            result.setMatched(true);
            result.setConfidence(0.65);
            result.setReason("存在重复提交请求，Redis 幂等 key 已拦截重复执行。");
        } else if (redisError) {
            result.setMatched(true);
            result.setConfidence(0.55);
            result.setReason("Redis 幂等检查出现异常，幂等保护存在可用性风险。");
        } else {
            result.setMatched(false);
            result.setConfidence(0.20);
            result.setReason(redisSetNxFailed
                    ? "请求命中过已有幂等 key，但暂未发现重复提交、参数冲突或 Redis 异常证据。"
                    : "暂未观察到重复提交、参数冲突或 Redis 幂等检查异常证据。");
        }

        result.setEvidence(evidence);
        return result;
    }

    private RuleDiagnosisResult baseResult(FaultExperiment experiment) {
        RuleDiagnosisResult result = new RuleDiagnosisResult();
        result.setExperimentId(experiment == null ? null : experiment.getExperimentId());
        result.setFaultType(ScenarioCode.IDEMPOTENCY_CONFLICT);
        result.setFaultName(FAULT_NAME);
        result.setSuggestions(DEFAULT_SUGGESTIONS);
        result.setEvidence(List.of());
        return result;
    }

    private List<String> buildEvidence(
            Optional<BigDecimal> requestCount,
            Optional<BigDecimal> duplicateCount,
            Optional<BigDecimal> conflictCount,
            Optional<BigDecimal> acceptedCount,
            Optional<BigDecimal> rejectedDuplicateCount,
            Optional<BigDecimal> hashMismatchCount,
            Optional<BigDecimal> redisSetNxSuccessCount,
            Optional<BigDecimal> redisSetNxFailCount,
            Optional<BigDecimal> redisErrorCount,
            Optional<BigDecimal> avgCheckDurationMs
    ) {
        List<String> evidence = new ArrayList<>();
        requestCount.ifPresent(value -> evidence.add("requestCount=" + value.longValue()));
        duplicateCount.ifPresent(value -> evidence.add("duplicateCount=" + value.longValue()));
        conflictCount.ifPresent(value -> evidence.add("conflictCount=" + value.longValue()));
        acceptedCount.ifPresent(value -> evidence.add("acceptedCount=" + value.longValue()));
        rejectedDuplicateCount.ifPresent(value -> evidence.add("rejectedDuplicateCount=" + value.longValue()));
        hashMismatchCount.ifPresent(value -> evidence.add("hashMismatchCount=" + value.longValue()));
        redisSetNxSuccessCount.ifPresent(value -> evidence.add("redisSetNxSuccessCount=" + value.longValue()));
        redisSetNxFailCount.ifPresent(value -> evidence.add("redisSetNxFailCount=" + value.longValue()));
        redisErrorCount.ifPresent(value -> evidence.add("redisErrorCount=" + value.longValue()));
        avgCheckDurationMs.ifPresent(value -> evidence.add("avgCheckDurationMs=" + value.longValue()));
        return evidence;
    }

    private Optional<BigDecimal> metric(Map<String, BigDecimal> metricValues, String metricName) {
        return Optional.ofNullable(metricValues.get(metricName));
    }

    private boolean greaterThanZero(Optional<BigDecimal> value) {
        return value.map(number -> number.compareTo(BigDecimal.ZERO) > 0).orElse(false);
    }
}
