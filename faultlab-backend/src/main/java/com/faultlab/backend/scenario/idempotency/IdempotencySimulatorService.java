package com.faultlab.backend.scenario.idempotency;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.trace.annotation.TraceSpan;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class IdempotencySimulatorService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofSeconds(60);
    private static final String COMPONENT = "Idempotency";

    private final StringRedisTemplate stringRedisTemplate;
    private final MetricService metricService;

    public IdempotencySimulatorService(StringRedisTemplate stringRedisTemplate, MetricService metricService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.metricService = metricService;
    }

    @TraceSpan(operationName = "idempotency.simulate", component = COMPONENT)
    public IdempotencySimulationResult simulate(
            String experimentId,
            List<IdempotencySimulatedRequest> requests,
            long processingDelayMs
    ) {
        IdempotencySimulationResult result = new IdempotencySimulationResult(requests == null ? 0 : requests.size());
        if (requests == null) {
            recordMetrics(experimentId, result, processingDelayMs);
            return result;
        }

        for (IdempotencySimulatedRequest request : requests) {
            result.add(checkRequest(experimentId, request, processingDelayMs));
        }
        recordMetrics(experimentId, result, processingDelayMs);
        return result;
    }

    @TraceSpan(operationName = "idempotency.check", component = "Redis")
    public IdempotencyCheckResult checkRequest(
            String experimentId,
            IdempotencySimulatedRequest request,
            long processingDelayMs
    ) {
        long startedAt = System.currentTimeMillis();
        try {
            String businessKey = businessKey(experimentId, request.getIdempotencyKey());
            String hashKey = hashKey(experimentId, request.getIdempotencyKey());
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(businessKey, "PROCESSING", IDEMPOTENCY_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                stringRedisTemplate.opsForValue().set(hashKey, request.getRequestHash(), IDEMPOTENCY_TTL);
                sleep(processingDelayMs);
                return result(IdempotencyCheckResult.Status.ACCEPTED, startedAt);
            }

            String existingHash = stringRedisTemplate.opsForValue().get(hashKey);
            if (Objects.equals(existingHash, request.getRequestHash())) {
                return result(IdempotencyCheckResult.Status.DUPLICATE, startedAt);
            }
            return result(IdempotencyCheckResult.Status.CONFLICT, startedAt);
        } catch (RuntimeException exception) {
            return result(IdempotencyCheckResult.Status.REDIS_ERROR, startedAt);
        }
    }

    private void recordMetrics(String experimentId, IdempotencySimulationResult result, long processingDelayMs) {
        metricService.recordMetric(experimentId, "requestCount", result.getRequestCount(), "count", COMPONENT);
        metricService.recordMetric(experimentId, "duplicateCount", result.getRejectedDuplicateCount(), "count", COMPONENT);
        metricService.recordMetric(experimentId, "conflictCount", result.getHashMismatchCount(), "count", COMPONENT);
        metricService.recordMetric(experimentId, "acceptedCount", result.getAcceptedCount(), "count", COMPONENT);
        metricService.recordMetric(experimentId, "rejectedDuplicateCount", result.getRejectedDuplicateCount(), "count", COMPONENT);
        metricService.recordMetric(experimentId, "hashMismatchCount", result.getHashMismatchCount(), "count", COMPONENT);
        metricService.recordMetric(experimentId, "redisSetNxSuccessCount", result.getRedisSetNxSuccessCount(), "count", COMPONENT);
        metricService.recordMetric(experimentId, "redisSetNxFailCount", result.getRedisSetNxFailCount(), "count", COMPONENT);
        metricService.recordMetric(experimentId, "redisErrorCount", result.getRedisErrorCount(), "count", COMPONENT);
        metricService.recordMetric(experimentId, "processingDelayMs", processingDelayMs, "ms", COMPONENT);
        metricService.recordMetric(experimentId, "avgCheckDurationMs", result.getAvgCheckDurationMs(), "ms", COMPONENT);
    }

    private IdempotencyCheckResult result(IdempotencyCheckResult.Status status, long startedAt) {
        return new IdempotencyCheckResult(status, System.currentTimeMillis() - startedAt);
    }

    private String businessKey(String experimentId, String idempotencyKey) {
        return "idempotency:" + experimentId + ":" + idempotencyKey;
    }

    private String hashKey(String experimentId, String idempotencyKey) {
        return businessKey(experimentId, idempotencyKey) + ":hash";
    }

    private void sleep(long processingDelayMs) {
        if (processingDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(processingDelayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
