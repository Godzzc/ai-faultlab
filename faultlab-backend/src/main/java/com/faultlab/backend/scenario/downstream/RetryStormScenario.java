package com.faultlab.backend.scenario.downstream;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.manager.TraceManager;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RetryStormScenario implements FaultScenario {

    public static final String SCENARIO_NAME = "重试风暴";
    public static final int DEFAULT_REQUEST_COUNT = 100;
    public static final int DEFAULT_CONCURRENCY = 20;
    public static final double DEFAULT_FAILURE_RATIO = 0.7D;
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final long DEFAULT_RETRY_BACKOFF_MS = 20L;
    public static final boolean DEFAULT_ENABLE_RETRY_LIMIT = false;
    public static final boolean DEFAULT_ENABLE_JITTER = false;

    private final MetricService metricService;
    private final TraceManager traceManager;

    public RetryStormScenario(MetricService metricService, TraceManager traceManager) {
        this.metricService = metricService;
        this.traceManager = traceManager;
    }

    @Override
    public String scenarioCode() {
        return ScenarioCode.RETRY_STORM;
    }

    @Override
    public String scenarioName() {
        return SCENARIO_NAME;
    }

    @Override
    public void execute(String experimentId, Map<String, Object> params) {
        int requestCount = Math.max(0, DownstreamScenarioSupport.readInt(params, "requestCount", DEFAULT_REQUEST_COUNT));
        int concurrency = Math.max(1, DownstreamScenarioSupport.readInt(params, "concurrency", DEFAULT_CONCURRENCY));
        double failureRatio = DownstreamScenarioSupport.clampRatio(DownstreamScenarioSupport.readDouble(
                params,
                "failureRatio",
                DEFAULT_FAILURE_RATIO
        ));
        int maxRetries = Math.max(0, DownstreamScenarioSupport.readInt(params, "maxRetries", DEFAULT_MAX_RETRIES));
        long retryBackoffMs = Math.max(0L, DownstreamScenarioSupport.readLong(
                params,
                "retryBackoffMs",
                DEFAULT_RETRY_BACKOFF_MS
        ));
        boolean enableRetryLimit = DownstreamScenarioSupport.readBoolean(
                params,
                "enableRetryLimit",
                DEFAULT_ENABLE_RETRY_LIMIT
        );
        boolean enableJitter = DownstreamScenarioSupport.readBoolean(params, "enableJitter", DEFAULT_ENABLE_JITTER);

        SimulationStats stats = simulate(
                requestCount,
                concurrency,
                failureRatio,
                maxRetries,
                retryBackoffMs,
                enableRetryLimit,
                enableJitter
        );

        DownstreamScenarioSupport.trace(traceManager, "downstream.call", () -> {
            DownstreamScenarioSupport.trace(traceManager, "retry.attempt", () -> {
            });
            DownstreamScenarioSupport.trace(traceManager, "retry.exhausted", () -> {
            });
        });

        recordMetrics(experimentId, stats);
    }

    private SimulationStats simulate(
            int requestCount,
            int concurrency,
            double failureRatio,
            int maxRetries,
            long retryBackoffMs,
            boolean enableRetryLimit,
            boolean enableJitter
    ) {
        SimulationStats stats = new SimulationStats();
        stats.initialRequestCount = requestCount;
        if (requestCount == 0) {
            return stats;
        }

        int failedInitialRequests = (int) Math.round(requestCount * failureRatio);
        int effectiveRetries = enableRetryLimit ? Math.min(maxRetries, 1) : maxRetries;
        int retryCount = failedInitialRequests * effectiveRetries;
        int totalCallCount = requestCount + retryCount;
        int exhaustedCount = failedInitialRequests;
        int successCount = Math.max(0, requestCount - failedInitialRequests);
        long jitterReductionMs = enableJitter ? Math.min(retryBackoffMs, 5L) : 0L;
        long perRetryLatencyMs = Math.max(1L, retryBackoffMs - jitterReductionMs);

        stats.totalCallCount = totalCallCount;
        stats.retryCount = retryCount;
        stats.retryExhaustedCount = exhaustedCount;
        stats.failureCount = failedInitialRequests + retryCount;
        stats.successCount = successCount;
        stats.amplificationFactor = (double) totalCallCount / requestCount;
        stats.apiErrorCount = exhaustedCount;
        stats.apiAvgLatencyMs = 10L + (long) Math.ceil((double) retryCount * perRetryLatencyMs / requestCount)
                + Math.max(0, concurrency / 10);
        return stats;
    }

    private void recordMetrics(String experimentId, SimulationStats stats) {
        double retryRate = stats.initialRequestCount == 0 ? 0D : (double) stats.retryCount / stats.initialRequestCount;
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.initial.request.count", stats.initialRequestCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.total.call.count", stats.totalCallCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.retry.count", stats.retryCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.retry.rate", retryRate, "ratio");
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.retry.exhausted.count", stats.retryExhaustedCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.failure.count", stats.failureCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.success.count", stats.successCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.retry.amplification.factor", stats.amplificationFactor, "ratio");
        DownstreamScenarioSupport.record(metricService, experimentId, "api.error.count", stats.apiErrorCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "api.avg.latency.ms", stats.apiAvgLatencyMs, "ms");
    }

    private static class SimulationStats {
        private int initialRequestCount;
        private int totalCallCount;
        private int retryCount;
        private int retryExhaustedCount;
        private int failureCount;
        private int successCount;
        private double amplificationFactor;
        private int apiErrorCount;
        private long apiAvgLatencyMs;
    }
}
