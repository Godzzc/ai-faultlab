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
public class DownstreamFailureRuleDiagnoser implements RuleDiagnoser {

    private static final List<String> TIMEOUT_SUGGESTIONS = List.of(
            "设置合理超时",
            "增加 fallback",
            "隔离下游调用线程池",
            "熔断降级",
            "减少同步依赖",
            "排查下游慢接口"
    );
    private static final List<String> RETRY_STORM_SUGGESTIONS = List.of(
            "限制重试次数",
            "增加指数退避和 jitter",
            "只对幂等请求重试",
            "接入熔断",
            "避免多层服务同时重试",
            "设置重试预算"
    );
    private static final List<String> CIRCUIT_BREAKER_SUGGESTIONS = List.of(
            "配置合理熔断阈值",
            "增加 fallback",
            "隔离下游调用",
            "限制重试",
            "监控半开恢复",
            "避免熔断阈值过低导致误伤"
    );

    @Override
    public boolean supports(String scenarioCode) {
        return ScenarioCode.DOWNSTREAM_TIMEOUT.equals(scenarioCode)
                || ScenarioCode.RETRY_STORM.equals(scenarioCode)
                || ScenarioCode.CIRCUIT_BREAKER_OPEN.equals(scenarioCode);
    }

    @Override
    public RuleDiagnosisResult diagnose(FaultExperiment experiment, List<FaultMetric> metrics) {
        Map<String, BigDecimal> metricValues = toMetricValues(metrics);
        String scenarioCode = experiment == null ? null : experiment.getScenarioCode();
        if (ScenarioCode.DOWNSTREAM_TIMEOUT.equals(scenarioCode)) {
            return diagnoseTimeout(experiment, metricValues);
        }
        if (ScenarioCode.RETRY_STORM.equals(scenarioCode)) {
            return diagnoseRetryStorm(experiment, metricValues);
        }
        return diagnoseCircuitBreakerOpen(experiment, metricValues);
    }

    private RuleDiagnosisResult diagnoseTimeout(FaultExperiment experiment, Map<String, BigDecimal> metrics) {
        Optional<BigDecimal> requestCount = metric(metrics, "downstream.request.count");
        Optional<BigDecimal> timeoutCount = metric(metrics, "downstream.timeout.count");
        Optional<BigDecimal> timeoutRate = metric(metrics, "downstream.timeout.rate");
        Optional<BigDecimal> avgLatencyMs = metric(metrics, "downstream.avg.latency.ms");
        Optional<BigDecimal> maxLatencyMs = metric(metrics, "downstream.max.latency.ms");
        Optional<BigDecimal> fallbackCount = metric(metrics, "downstream.fallback.count");
        Optional<BigDecimal> apiErrorCount = metric(metrics, "api.error.count");

        RuleDiagnosisResult result = baseResult(experiment, ScenarioCode.DOWNSTREAM_TIMEOUT, "下游接口超时", TIMEOUT_SUGGESTIONS);
        result.setEvidence(evidence(
                item("requestCount", requestCount),
                item("timeoutCount", timeoutCount),
                item("timeoutRate", timeoutRate),
                item("avgLatencyMs", avgLatencyMs),
                item("maxLatencyMs", maxLatencyMs),
                item("fallbackCount", fallbackCount),
                item("apiErrorCount", apiErrorCount)
        ));

        boolean highTimeoutRate = greaterOrEqual(timeoutRate, BigDecimal.valueOf(0.50D));
        boolean highAvgLatency = greaterOrEqual(avgLatencyMs, BigDecimal.valueOf(100D));
        boolean highMaxLatency = greaterOrEqual(maxLatencyMs, BigDecimal.valueOf(100D));
        boolean errorsRaised = greaterThanZero(apiErrorCount);
        boolean noFallbackWithTimeouts = greaterThanZero(timeoutCount) && !greaterThanZero(fallbackCount);

        if (highTimeoutRate || highAvgLatency || highMaxLatency || errorsRaised || noFallbackWithTimeouts) {
            result.setMatched(true);
            result.setConfidence(errorsRaised || noFallbackWithTimeouts ? 0.90 : 0.78);
            result.setReason("下游服务响应时间超过调用超时时间，导致当前接口延迟升高或请求失败");
        } else {
            result.setMatched(false);
            result.setConfidence(0.30);
            result.setReason("下游调用超时、错误和延迟指标较低，暂未命中下游接口超时高风险规则");
        }
        return result;
    }

    private RuleDiagnosisResult diagnoseRetryStorm(FaultExperiment experiment, Map<String, BigDecimal> metrics) {
        Optional<BigDecimal> initialRequestCount = metric(metrics, "downstream.initial.request.count");
        Optional<BigDecimal> totalCallCount = metric(metrics, "downstream.total.call.count");
        Optional<BigDecimal> retryCount = metric(metrics, "downstream.retry.count");
        Optional<BigDecimal> retryRate = metric(metrics, "downstream.retry.rate");
        Optional<BigDecimal> retryExhaustedCount = metric(metrics, "downstream.retry.exhausted.count");
        Optional<BigDecimal> amplificationFactor = metric(metrics, "downstream.retry.amplification.factor");
        Optional<BigDecimal> apiErrorCount = metric(metrics, "api.error.count");

        RuleDiagnosisResult result = baseResult(experiment, ScenarioCode.RETRY_STORM, "重试风暴", RETRY_STORM_SUGGESTIONS);
        result.setEvidence(evidence(
                item("requestCount", initialRequestCount),
                item("totalCallCount", totalCallCount),
                item("retryCount", retryCount),
                item("retryRate", retryRate),
                item("amplificationFactor", amplificationFactor),
                item("retryExhaustedCount", retryExhaustedCount),
                item("apiErrorCount", apiErrorCount)
        ));

        boolean manyRetries = greaterOrEqual(retryCount, BigDecimal.valueOf(20D));
        boolean callsAmplified = initialRequestCount.isPresent() && totalCallCount
                .map(value -> value.compareTo(initialRequestCount.get().multiply(BigDecimal.valueOf(1.50D))) >= 0)
                .orElse(false);
        boolean highAmplification = greaterOrEqual(amplificationFactor, BigDecimal.valueOf(1.50D));
        boolean exhaustedRetries = greaterThanZero(retryExhaustedCount);

        if (manyRetries || callsAmplified || highAmplification || exhaustedRetries) {
            result.setMatched(true);
            result.setConfidence(highAmplification || exhaustedRetries ? 0.90 : 0.78);
            result.setReason("下游失败后上游大量重试，导致下游调用量被放大，进一步加重系统压力");
        } else {
            result.setMatched(false);
            result.setConfidence(0.30);
            result.setReason("重试次数和调用放大倍数较低，暂未命中重试风暴高风险规则");
        }
        return result;
    }

    private RuleDiagnosisResult diagnoseCircuitBreakerOpen(FaultExperiment experiment, Map<String, BigDecimal> metrics) {
        Optional<BigDecimal> requestCount = metric(metrics, "circuit.request.count");
        Optional<BigDecimal> failureCount = metric(metrics, "circuit.failure.count");
        Optional<BigDecimal> failureRate = metric(metrics, "circuit.failure.rate");
        Optional<BigDecimal> slowCallCount = metric(metrics, "circuit.slow.call.count");
        Optional<BigDecimal> slowCallRate = metric(metrics, "circuit.slow.call.rate");
        Optional<BigDecimal> openCount = metric(metrics, "circuit.open.count");
        Optional<BigDecimal> rejectedCount = metric(metrics, "circuit.rejected.count");
        Optional<BigDecimal> fallbackCount = metric(metrics, "circuit.fallback.count");
        Optional<BigDecimal> skippedDownstreamCallCount = metric(metrics, "downstream.call.skipped.count");

        RuleDiagnosisResult result = baseResult(
                experiment,
                ScenarioCode.CIRCUIT_BREAKER_OPEN,
                "熔断器打开 / 熔断降级",
                CIRCUIT_BREAKER_SUGGESTIONS
        );
        result.setEvidence(evidence(
                item("requestCount", requestCount),
                item("failureCount", failureCount),
                item("failureRate", failureRate),
                item("slowCallCount", slowCallCount),
                item("slowCallRate", slowCallRate),
                item("openCount", openCount),
                item("rejectedCount", rejectedCount),
                item("fallbackCount", fallbackCount),
                item("skippedDownstreamCallCount", skippedDownstreamCallCount)
        ));

        boolean highFailureRate = greaterOrEqual(failureRate, BigDecimal.valueOf(0.50D));
        boolean highSlowCallRate = greaterOrEqual(slowCallRate, BigDecimal.valueOf(0.50D));
        boolean circuitOpened = greaterThanZero(openCount);
        boolean rejectedRequests = greaterThanZero(rejectedCount);
        boolean skippedCalls = greaterThanZero(skippedDownstreamCallCount);

        if (highFailureRate || highSlowCallRate || circuitOpened || rejectedRequests || skippedCalls) {
            result.setMatched(true);
            result.setConfidence(circuitOpened || rejectedRequests || skippedCalls ? 0.90 : 0.80);
            result.setReason("下游失败率或慢调用比例超过阈值，熔断器打开，后续请求被快速拒绝或进入 fallback");
        } else {
            result.setMatched(false);
            result.setConfidence(0.30);
            result.setReason("熔断器失败率、慢调用比例和拒绝请求指标较低，暂未命中熔断打开规则");
        }
        return result;
    }

    private RuleDiagnosisResult baseResult(
            FaultExperiment experiment,
            String faultType,
            String faultName,
            List<String> suggestions
    ) {
        RuleDiagnosisResult result = new RuleDiagnosisResult();
        result.setExperimentId(experiment == null ? null : experiment.getExperimentId());
        result.setFaultType(faultType);
        result.setFaultName(faultName);
        result.setSuggestions(suggestions);
        result.setEvidence(List.of());
        return result;
    }

    private Map<String, BigDecimal> toMetricValues(List<FaultMetric> metrics) {
        return metrics == null ? Map.of() : metrics.stream()
                .filter(metric -> metric.getMetricName() != null && metric.getMetricValue() != null)
                .collect(Collectors.toMap(
                        FaultMetric::getMetricName,
                        FaultMetric::getMetricValue,
                        (left, right) -> right
                ));
    }

    @SafeVarargs
    private final List<String> evidence(Optional<String>... items) {
        List<String> evidence = new ArrayList<>();
        for (Optional<String> item : items) {
            item.ifPresent(evidence::add);
        }
        return evidence;
    }

    private Optional<String> item(String name, Optional<BigDecimal> value) {
        return value.map(number -> name + "=" + number.stripTrailingZeros().toPlainString());
    }

    private Optional<BigDecimal> metric(Map<String, BigDecimal> metricValues, String metricName) {
        return Optional.ofNullable(metricValues.get(metricName));
    }

    private boolean greaterThanZero(Optional<BigDecimal> value) {
        return value.map(number -> number.compareTo(BigDecimal.ZERO) > 0).orElse(false);
    }

    private boolean greaterOrEqual(Optional<BigDecimal> value, BigDecimal threshold) {
        return value.map(number -> number.compareTo(threshold) >= 0).orElse(false);
    }
}
