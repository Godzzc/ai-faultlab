package com.faultlab.backend.experiment.remediation;

import com.faultlab.backend.experiment.dto.ExpectedMetricDirection;
import com.faultlab.backend.experiment.dto.ExpectedMetricEffect;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RemediationValidationPolicy {

    public boolean supports(String scenarioCode) {
        return ScenarioCode.CACHE_BREAKDOWN.equals(scenarioCode)
                || ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION.equals(scenarioCode)
                || ScenarioCode.DOWNSTREAM_TIMEOUT.equals(scenarioCode)
                || ScenarioCode.RETRY_STORM.equals(scenarioCode);
    }

    public List<ExpectedMetricEffect> resolveExpectedEffects(
            String scenarioCode,
            Map<String, Object> originalParams,
            Map<String, Object> replayParams,
            Map<String, Object> appliedPatch
    ) {
        if (ScenarioCode.CACHE_BREAKDOWN.equals(scenarioCode)) {
            return cacheBreakdownEffects(appliedPatch);
        }
        if (ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION.equals(scenarioCode)) {
            return dbConnectionPoolEffects(appliedPatch);
        }
        if (ScenarioCode.DOWNSTREAM_TIMEOUT.equals(scenarioCode)) {
            return downstreamTimeoutEffects(appliedPatch);
        }
        if (ScenarioCode.RETRY_STORM.equals(scenarioCode)) {
            return retryStormEffects(originalParams, replayParams, appliedPatch);
        }
        return List.of();
    }

    private List<ExpectedMetricEffect> cacheBreakdownEffects(Map<String, Object> appliedPatch) {
        List<ExpectedMetricEffect> effects = new ArrayList<>();
        if (patchedTrue(appliedPatch, "enableMutex")) {
            effects.add(required("cache.concurrent.rebuild.count", ExpectedMetricDirection.DECREASE,
                    "Concurrent cache rebuild pressure should fall."));
            effects.add(required("cache.rebuild.count", ExpectedMetricDirection.DECREASE,
                    "Duplicate hot-key cache rebuilds should be reduced."));
            effects.add(required("cache.db.query.count", ExpectedMetricDirection.DECREASE,
                    "Hot-key DB query pressure should be reduced."));
        }
        if (patchedTrue(appliedPatch, "enableLogicalExpire")) {
            effects.add(required("cache.hot.key.miss.count", ExpectedMetricDirection.DECREASE,
                    "Logical expire should serve hot-key reads without cache misses."));
            effects.add(required("cache.miss.rate", ExpectedMetricDirection.DECREASE,
                    "Hot-key cache miss rate should fall."));
            effects.add(required("cache.db.query.count", ExpectedMetricDirection.DECREASE,
                    "Logical expire should reduce foreground DB query pressure."));
        }
        return deduplicate(effects);
    }

    private List<ExpectedMetricEffect> dbConnectionPoolEffects(Map<String, Object> appliedPatch) {
        if (!patchedTrue(appliedPatch, "enableFastRelease")) {
            return List.of();
        }
        return List.of(
                required("db.connection.acquire.timeout.count", ExpectedMetricDirection.DECREASE,
                        "Fewer requests should time out while acquiring a connection."),
                required("db.connection.acquire.avg.ms", ExpectedMetricDirection.DECREASE,
                        "Average connection acquisition latency should fall."),
                required("db.connection.hold.avg.ms", ExpectedMetricDirection.DECREASE,
                        "Average connection hold time should fall."),
                required("api.error.count", ExpectedMetricDirection.DECREASE,
                        "API errors caused by connection acquisition timeouts should fall.")
        );
    }

    private List<ExpectedMetricEffect> downstreamTimeoutEffects(Map<String, Object> appliedPatch) {
        if (!patchedTrue(appliedPatch, "enableFallback")) {
            return List.of();
        }
        return List.of(
                required("api.error.count", ExpectedMetricDirection.DECREASE,
                        "Timeouts should no longer surface as API errors when fallback is enabled."),
                required("downstream.fallback.count", ExpectedMetricDirection.INCREASE,
                        "Fallback executions should increase for timed-out downstream calls."),
                required("downstream.timeout.count", ExpectedMetricDirection.STABLE,
                        "Fallback does not make the downstream dependency faster in the current simulation.")
        );
    }

    private List<ExpectedMetricEffect> retryStormEffects(
            Map<String, Object> originalParams,
            Map<String, Object> replayParams,
            Map<String, Object> appliedPatch
    ) {
        List<ExpectedMetricEffect> effects = new ArrayList<>();
        if (patchedTrue(appliedPatch, "enableRetryLimit")) {
            effects.add(required("downstream.retry.count", ExpectedMetricDirection.DECREASE,
                    "Retry traffic should be reduced by the retry limit."));
            effects.add(required("downstream.retry.amplification.factor", ExpectedMetricDirection.DECREASE,
                    "Retry amplification should fall as retry traffic is capped."));
            effects.add(required("downstream.total.call.count", ExpectedMetricDirection.DECREASE,
                    "Total downstream call volume should fall as retries are capped."));
            effects.add(required("downstream.retry.exhausted.count", ExpectedMetricDirection.STABLE,
                    "The current simulation derives exhausted count from initial failures, not retry policy."));
        }
        if (patchedTrue(appliedPatch, "enableJitter")) {
            effects.add(required("api.avg.latency.ms", ExpectedMetricDirection.DECREASE,
                    "Retry jitter should reduce average API latency in the simulation."));
        }
        if (appliedPatch.containsKey("retryBackoffMs") && replayBackoffIsLower(originalParams, replayParams)) {
            effects.add(required("api.avg.latency.ms", ExpectedMetricDirection.DECREASE,
                    "Lower retry backoff should reduce average API latency in the simulation."));
        }
        return deduplicate(effects);
    }

    private ExpectedMetricEffect required(
            String metricName,
            ExpectedMetricDirection direction,
            String description
    ) {
        return new ExpectedMetricEffect(metricName, direction, description, true);
    }

    private boolean patchedTrue(Map<String, Object> appliedPatch, String parameterName) {
        return Boolean.TRUE.equals(appliedPatch.get(parameterName));
    }

    private boolean replayBackoffIsLower(Map<String, Object> originalParams, Map<String, Object> replayParams) {
        Long original = asLong(originalParams.get("retryBackoffMs"));
        Long replay = asLong(replayParams.get("retryBackoffMs"));
        return original != null && replay != null && replay < original;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return null;
    }

    private List<ExpectedMetricEffect> deduplicate(List<ExpectedMetricEffect> effects) {
        List<ExpectedMetricEffect> deduplicated = new ArrayList<>();
        for (ExpectedMetricEffect effect : effects) {
            boolean exists = deduplicated.stream()
                    .anyMatch(current -> current.getMetricName().equals(effect.getMetricName())
                            && current.getDirection() == effect.getDirection());
            if (!exists) {
                deduplicated.add(effect);
            }
        }
        return deduplicated;
    }
}
