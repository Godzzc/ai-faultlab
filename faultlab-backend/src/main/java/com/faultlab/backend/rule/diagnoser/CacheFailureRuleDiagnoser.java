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
public class CacheFailureRuleDiagnoser implements RuleDiagnoser {

    private static final List<String> PENETRATION_SUGGESTIONS = List.of(
            "参数校验",
            "缓存空值",
            "布隆过滤器/布谷鸟过滤器",
            "非法 key 限流",
            "热点非法 key 黑名单"
    );
    private static final List<String> BREAKDOWN_SUGGESTIONS = List.of(
            "互斥锁重建",
            "逻辑过期",
            "热点 key 永不过期",
            "异步刷新",
            "singleflight",
            "本地缓存兜底"
    );
    private static final List<String> AVALANCHE_SUGGESTIONS = List.of(
            "TTL 随机化",
            "缓存预热",
            "多级缓存",
            "Redis 高可用",
            "限流降级",
            "熔断保护",
            "本地缓存兜底"
    );

    @Override
    public boolean supports(String scenarioCode) {
        return ScenarioCode.CACHE_PENETRATION.equals(scenarioCode)
                || ScenarioCode.CACHE_BREAKDOWN.equals(scenarioCode)
                || ScenarioCode.CACHE_AVALANCHE.equals(scenarioCode);
    }

    @Override
    public RuleDiagnosisResult diagnose(FaultExperiment experiment, List<FaultMetric> metrics) {
        Map<String, BigDecimal> metricValues = toMetricValues(metrics);
        String scenarioCode = experiment == null ? null : experiment.getScenarioCode();
        if (ScenarioCode.CACHE_PENETRATION.equals(scenarioCode)) {
            return diagnosePenetration(experiment, metricValues);
        }
        if (ScenarioCode.CACHE_BREAKDOWN.equals(scenarioCode)) {
            return diagnoseBreakdown(experiment, metricValues);
        }
        return diagnoseAvalanche(experiment, metricValues);
    }

    private RuleDiagnosisResult diagnosePenetration(FaultExperiment experiment, Map<String, BigDecimal> metrics) {
        Optional<BigDecimal> requestCount = metric(metrics, "cache.request.count");
        Optional<BigDecimal> cacheMissRate = metric(metrics, "cache.miss.rate");
        Optional<BigDecimal> dbQueryCount = metric(metrics, "cache.db.query.count");
        Optional<BigDecimal> invalidKeyCount = metric(metrics, "cache.invalid.key.count");
        Optional<BigDecimal> bloomRejectCount = metric(metrics, "cache.bloom.reject.count");
        Optional<BigDecimal> nullCacheWriteCount = metric(metrics, "cache.null.cache.write.count");

        RuleDiagnosisResult result = baseResult(experiment, ScenarioCode.CACHE_PENETRATION, "缓存穿透", PENETRATION_SUGGESTIONS);
        List<String> evidence = evidence(
                item("requestCount", requestCount),
                item("cacheMissRate", cacheMissRate),
                item("dbQueryCount", dbQueryCount),
                item("invalidKeyCount", invalidKeyCount),
                item("bloomRejectCount", bloomRejectCount),
                item("nullCacheWriteCount", nullCacheWriteCount)
        );
        result.setEvidence(evidence);

        boolean highMissRate = greaterOrEqual(cacheMissRate, BigDecimal.valueOf(0.70D));
        boolean dbCloseToRequests = requestCount.isPresent() && dbQueryCount
                .map(value -> value.compareTo(requestCount.get().multiply(BigDecimal.valueOf(0.70D))) >= 0)
                .orElse(false);
        boolean manyInvalidKeys = requestCount.isPresent() && invalidKeyCount
                .map(value -> value.compareTo(requestCount.get().multiply(BigDecimal.valueOf(0.50D))) >= 0)
                .orElse(false);
        boolean noMitigation = !greaterThanZero(bloomRejectCount) && !greaterThanZero(nullCacheWriteCount);

        if (highMissRate || dbCloseToRequests || manyInvalidKeys || noMitigation) {
            result.setMatched(true);
            result.setConfidence(noMitigation && (dbCloseToRequests || manyInvalidKeys) ? 0.90 : 0.75);
            result.setReason("大量不存在 key 未被过滤或空值缓存，请求持续穿透到 DB");
        } else {
            result.setMatched(false);
            result.setConfidence(0.30);
            result.setReason("缓存穿透保护指标已生效，暂未命中高风险规则");
        }
        return result;
    }

    private RuleDiagnosisResult diagnoseBreakdown(FaultExperiment experiment, Map<String, BigDecimal> metrics) {
        Optional<BigDecimal> requestCount = metric(metrics, "cache.hot.key.request.count");
        Optional<BigDecimal> hotKeyMissCount = metric(metrics, "cache.hot.key.miss.count");
        Optional<BigDecimal> dbQueryCount = metric(metrics, "cache.db.query.count");
        Optional<BigDecimal> rebuildCount = metric(metrics, "cache.rebuild.count");
        Optional<BigDecimal> lockAcquireCount = metric(metrics, "cache.lock.acquire.count");
        Optional<BigDecimal> lockFailCount = metric(metrics, "cache.lock.fail.count");
        Optional<BigDecimal> concurrentRebuildCount = metric(metrics, "cache.concurrent.rebuild.count");

        RuleDiagnosisResult result = baseResult(experiment, ScenarioCode.CACHE_BREAKDOWN, "缓存击穿", BREAKDOWN_SUGGESTIONS);
        result.setEvidence(evidence(
                item("requestCount", requestCount),
                item("hotKeyMissCount", hotKeyMissCount),
                item("dbQueryCount", dbQueryCount),
                item("rebuildCount", rebuildCount),
                item("lockAcquireCount", lockAcquireCount),
                item("lockFailCount", lockFailCount)
        ));

        boolean hotKeyConcentrated = greaterOrEqual(requestCount, BigDecimal.valueOf(20));
        boolean dbSpike = requestCount.isPresent() && dbQueryCount
                .map(value -> value.compareTo(requestCount.get().multiply(BigDecimal.valueOf(0.50D))) >= 0)
                .orElse(false);
        boolean rebuildTooHigh = greaterOrEqual(rebuildCount, BigDecimal.valueOf(5));
        boolean concurrentRebuildHigh = greaterOrEqual(concurrentRebuildCount, BigDecimal.valueOf(2));

        if (hotKeyConcentrated && (dbSpike || rebuildTooHigh || concurrentRebuildHigh)) {
            result.setMatched(true);
            result.setConfidence(0.88);
            result.setReason("热点 key 过期后大量并发请求同时穿透到 DB，并重复触发缓存重建");
        } else {
            result.setMatched(false);
            result.setConfidence(0.35);
            result.setReason("热点 key 访问已被互斥或逻辑过期保护，暂未命中缓存击穿高风险规则");
        }
        return result;
    }

    private RuleDiagnosisResult diagnoseAvalanche(FaultExperiment experiment, Map<String, BigDecimal> metrics) {
        Optional<BigDecimal> keyCount = metric(metrics, "cache.key.count");
        Optional<BigDecimal> expiredKeyCount = metric(metrics, "cache.expired.key.count");
        Optional<BigDecimal> cacheMissRate = metric(metrics, "cache.miss.rate");
        Optional<BigDecimal> dbQueryCount = metric(metrics, "cache.db.query.count");
        Optional<BigDecimal> cacheUnavailableCount = metric(metrics, "cache.unavailable.count");
        Optional<BigDecimal> fallbackCount = metric(metrics, "cache.fallback.count");
        Optional<BigDecimal> requestErrorCount = metric(metrics, "cache.request.error.count");

        RuleDiagnosisResult result = baseResult(experiment, ScenarioCode.CACHE_AVALANCHE, "缓存雪崩", AVALANCHE_SUGGESTIONS);
        result.setEvidence(evidence(
                item("keyCount", keyCount),
                item("expiredKeyCount", expiredKeyCount),
                item("cacheMissRate", cacheMissRate),
                item("dbQueryCount", dbQueryCount),
                item("cacheUnavailableCount", cacheUnavailableCount),
                item("fallbackCount", fallbackCount),
                item("requestErrorCount", requestErrorCount)
        ));

        boolean manyExpiredKeys = greaterOrEqual(expiredKeyCount, BigDecimal.valueOf(2));
        boolean highMissRate = greaterOrEqual(cacheMissRate, BigDecimal.valueOf(0.50D));
        boolean dbSpike = greaterOrEqual(dbQueryCount, BigDecimal.valueOf(20));
        boolean redisDownWithoutFallback = greaterThanZero(cacheUnavailableCount)
                && fallbackCount.map(value -> value.compareTo(cacheUnavailableCount.orElse(BigDecimal.ZERO)) < 0).orElse(true);
        boolean errorsRaised = greaterThanZero(requestErrorCount);

        if (manyExpiredKeys || highMissRate || dbSpike || redisDownWithoutFallback || errorsRaised) {
            result.setMatched(true);
            result.setConfidence(redisDownWithoutFallback || errorsRaised ? 0.90 : 0.82);
            result.setReason("大量缓存 key 同时失效或缓存服务不可用，导致请求集中打到 DB");
        } else {
            result.setMatched(false);
            result.setConfidence(0.30);
            result.setReason("缓存失效范围和 DB 压力较低，暂未命中缓存雪崩高风险规则");
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
