package com.faultlab.backend.scenario.idempotency;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.trace.annotation.TraceSpan;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class IdempotencySimulatorService {

    private static final String COMPONENT = "Idempotency";

    private final IdempotencyCheckService idempotencyCheckService;
    private final MetricService metricService;

    public IdempotencySimulatorService(IdempotencyCheckService idempotencyCheckService, MetricService metricService) {
        this.idempotencyCheckService = idempotencyCheckService;
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
            result.add(idempotencyCheckService.checkRequest(experimentId, request, processingDelayMs));
        }
        recordMetrics(experimentId, result, processingDelayMs);
        return result;
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

}
