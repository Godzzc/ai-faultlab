package com.faultlab.backend.scenario.impl;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.idempotency.IdempotencySimulatedRequest;
import com.faultlab.backend.scenario.idempotency.IdempotencySimulatorService;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.annotation.TraceSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyConflictScenario implements FaultScenario {

    public static final String SCENARIO_NAME = "幂等冲突";
    public static final int DEFAULT_REQUEST_COUNT = 20;
    public static final int DEFAULT_CONFLICT_COUNT = 5;
    public static final int DEFAULT_DUPLICATE_COUNT = 10;
    public static final long DEFAULT_PROCESSING_DELAY_MS = 1000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(IdempotencyConflictScenario.class);
    private static final String COMPONENT = "Idempotency";

    private final ThreadPoolExecutor faultLabScenarioExecutor;
    private final IdempotencySimulatorService idempotencySimulatorService;
    private final MetricService metricService;

    public IdempotencyConflictScenario(
            @Qualifier("faultLabScenarioExecutor") ThreadPoolExecutor faultLabScenarioExecutor,
            IdempotencySimulatorService idempotencySimulatorService,
            MetricService metricService
    ) {
        this.faultLabScenarioExecutor = faultLabScenarioExecutor;
        this.idempotencySimulatorService = idempotencySimulatorService;
        this.metricService = metricService;
    }

    @Override
    public String scenarioCode() {
        return ScenarioCode.IDEMPOTENCY_CONFLICT;
    }

    @Override
    public String scenarioName() {
        return SCENARIO_NAME;
    }

    @Override
    @TraceSpan(operationName = "idempotency.simulate", component = COMPONENT)
    public void execute(String experimentId, Map<String, Object> params) {
        int requestCount = readInt(params, "requestCount", DEFAULT_REQUEST_COUNT);
        int duplicateCount = readInt(params, "duplicateCount", DEFAULT_DUPLICATE_COUNT);
        int conflictCount = readInt(params, "conflictCount", DEFAULT_CONFLICT_COUNT);
        long processingDelayMs = readLong(params, "processingDelayMs", DEFAULT_PROCESSING_DELAY_MS);
        String idempotencyKey = readString(params, "idempotencyKey", "idem_" + UUID.randomUUID().toString().replace("-", ""));

        List<IdempotencySimulatedRequest> requests = buildRequests(requestCount, duplicateCount, conflictCount, idempotencyKey);
        try {
            // TODO: Propagate TraceContext with a TaskDecorator or wrapped Runnable in a later iteration.
            faultLabScenarioExecutor.execute(() -> idempotencySimulatorService.simulate(experimentId, requests, processingDelayMs));
        } catch (RejectedExecutionException exception) {
            recordRejectedBatchMetrics(experimentId, requestCount, duplicateCount, conflictCount, processingDelayMs);
        }
    }

    private List<IdempotencySimulatedRequest> buildRequests(
            int requestCount,
            int duplicateCount,
            int conflictCount,
            String idempotencyKey
    ) {
        int normalizedRequestCount = Math.max(requestCount, 0);
        int normalizedDuplicateCount = Math.max(duplicateCount, 0);
        int normalizedConflictCount = Math.max(conflictCount, 0);
        List<IdempotencySimulatedRequest> requests = new ArrayList<>();
        String originalHash = "request_hash_original";
        for (int index = 0; index < normalizedRequestCount; index++) {
            String requestHash = originalHash;
            if (index > normalizedDuplicateCount && index <= normalizedDuplicateCount + normalizedConflictCount) {
                requestHash = "request_hash_conflict_" + index;
            }
            requests.add(new IdempotencySimulatedRequest(idempotencyKey, requestHash, index));
        }
        return requests;
    }

    private void recordRejectedBatchMetrics(
            String experimentId,
            int requestCount,
            int duplicateCount,
            int conflictCount,
            long processingDelayMs
    ) {
        recordMetric(experimentId, "requestCount", requestCount, "count");
        recordMetric(experimentId, "duplicateCount", duplicateCount, "count");
        recordMetric(experimentId, "conflictCount", conflictCount, "count");
        recordMetric(experimentId, "acceptedCount", 0, "count");
        recordMetric(experimentId, "rejectedDuplicateCount", 0, "count");
        recordMetric(experimentId, "hashMismatchCount", 0, "count");
        recordMetric(experimentId, "redisSetNxSuccessCount", 0, "count");
        recordMetric(experimentId, "redisSetNxFailCount", 0, "count");
        recordMetric(experimentId, "redisErrorCount", requestCount, "count");
        recordMetric(experimentId, "processingDelayMs", processingDelayMs, "ms");
        recordMetric(experimentId, "avgCheckDurationMs", 0L, "ms");
    }

    private void recordMetric(String experimentId, String metricName, Number metricValue, String metricUnit) {
        try {
            metricService.recordMetric(experimentId, metricName, metricValue, metricUnit, COMPONENT);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to record idempotency metric. experimentId={}, metricName={}", experimentId, metricName, exception);
        }
    }

    private int readInt(Map<String, Object> params, String key, int defaultValue) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return defaultValue;
    }

    private long readLong(Map<String, Object> params, String key, long defaultValue) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return defaultValue;
    }

    private String readString(Map<String, Object> params, String key, String defaultValue) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return defaultValue;
    }
}
