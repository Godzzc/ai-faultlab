package com.faultlab.backend.experiment.remediation;

import com.faultlab.backend.common.BusinessException;
import com.faultlab.backend.common.ErrorCode;
import com.faultlab.backend.scenario.model.ScenarioCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

@Component
public class RemediationReplayPolicy {

    private static final Set<String> FORBIDDEN_PATCH_KEYS = Set.of(
            "scenarioCode",
            "scenario_code",
            "experimentId",
            "experiment_id"
    );

    private final Map<String, Map<String, ParameterRule>> policyByScenario = Map.of(
            ScenarioCode.CACHE_BREAKDOWN,
            Map.of(
                    "enableMutex", ParameterRule.requiredBooleanTrue("enableMutex"),
                    "enableLogicalExpire", ParameterRule.requiredBooleanTrue("enableLogicalExpire")
            ),
            ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION,
            Map.of("enableFastRelease", ParameterRule.requiredBooleanTrue("enableFastRelease")),
            ScenarioCode.DOWNSTREAM_TIMEOUT,
            Map.of("enableFallback", ParameterRule.requiredBooleanTrue("enableFallback")),
            ScenarioCode.RETRY_STORM,
            Map.of(
                    "enableRetryLimit", ParameterRule.requiredBooleanTrue("enableRetryLimit"),
                    "enableJitter", ParameterRule.requiredBooleanTrue("enableJitter"),
                    "retryBackoffMs", ParameterRule.integerRange("retryBackoffMs", 1, 5000)
            )
    );

    public boolean supports(String scenarioCode) {
        return policyByScenario.containsKey(scenarioCode);
    }

    public Map<String, Object> validateAndNormalize(String scenarioCode, Map<String, Object> parameterPatch) {
        Map<String, ParameterRule> scenarioPolicy = policyByScenario.get(scenarioCode);
        if (scenarioPolicy == null) {
            throw new BusinessException(
                    ErrorCode.REMEDIATION_REPLAY_UNSUPPORTED_SCENARIO,
                    "remediation replay unsupported for scenario: " + scenarioCode
            );
        }
        if (CollectionUtils.isEmpty(parameterPatch)) {
            throw new BusinessException(ErrorCode.INVALID_REMEDIATION_PATCH, "parameterPatch is required");
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : parameterPatch.entrySet()) {
            String parameterName = entry.getKey();
            if (FORBIDDEN_PATCH_KEYS.contains(parameterName) || !scenarioPolicy.containsKey(parameterName)) {
                throw new BusinessException(
                        ErrorCode.REMEDIATION_PARAMETER_NOT_ALLOWED,
                        "remediation parameter is not allowed: " + parameterName
                );
            }
            normalized.put(parameterName, scenarioPolicy.get(parameterName).normalize(entry.getValue()));
        }
        return normalized;
    }

    private static final class ParameterRule {

        private enum Kind {
            BOOLEAN_TRUE,
            INTEGER_RANGE
        }

        private final String name;
        private final Kind kind;
        private final int minValue;
        private final int maxValue;

        private ParameterRule(String name, Kind kind, int minValue, int maxValue) {
            this.name = name;
            this.kind = kind;
            this.minValue = minValue;
            this.maxValue = maxValue;
        }

        static ParameterRule requiredBooleanTrue(String name) {
            return new ParameterRule(name, Kind.BOOLEAN_TRUE, 0, 0);
        }

        static ParameterRule integerRange(String name, int minValue, int maxValue) {
            return new ParameterRule(name, Kind.INTEGER_RANGE, minValue, maxValue);
        }

        Object normalize(Object value) {
            return switch (kind) {
                case BOOLEAN_TRUE -> normalizeBooleanTrue(value);
                case INTEGER_RANGE -> normalizeIntegerRange(value);
            };
        }

        private Boolean normalizeBooleanTrue(Object value) {
            if (!(value instanceof Boolean bool)) {
                throw new BusinessException(
                        ErrorCode.INVALID_REMEDIATION_PATCH,
                        name + " must be a boolean"
                );
            }
            if (!bool) {
                throw new BusinessException(
                        ErrorCode.INVALID_REMEDIATION_PATCH,
                        name + " must be true"
                );
            }
            return true;
        }

        private Integer normalizeIntegerRange(Object value) {
            if (!(value instanceof Number number) || isFractional(number)) {
                throw new BusinessException(
                        ErrorCode.INVALID_REMEDIATION_PATCH,
                        name + " must be an integer"
                );
            }
            long longValue = number.longValue();
            if (longValue < minValue || longValue > maxValue) {
                throw new BusinessException(
                        ErrorCode.INVALID_REMEDIATION_PATCH,
                        name + " must be between " + minValue + " and " + maxValue
                );
            }
            return Math.toIntExact(longValue);
        }

        private boolean isFractional(Number number) {
            return number.doubleValue() % 1D != 0D;
        }
    }
}
