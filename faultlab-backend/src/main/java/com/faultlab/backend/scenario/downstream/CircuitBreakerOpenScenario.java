package com.faultlab.backend.scenario.downstream;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.manager.TraceManager;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CircuitBreakerOpenScenario implements FaultScenario {

    public static final String SCENARIO_NAME = "熔断器打开 / 熔断降级";
    public static final int DEFAULT_REQUEST_COUNT = 100;
    public static final double DEFAULT_FAILURE_RATIO = 0.8D;
    public static final double DEFAULT_SLOW_CALL_RATIO = 0.5D;
    public static final int DEFAULT_SLIDING_WINDOW_SIZE = 20;
    public static final double DEFAULT_FAILURE_RATE_THRESHOLD = 0.5D;
    public static final long DEFAULT_SLOW_CALL_THRESHOLD_MS = 200L;
    public static final long DEFAULT_OPEN_DURATION_MS = 500L;
    public static final boolean DEFAULT_ENABLE_FALLBACK = true;

    private final MetricService metricService;
    private final TraceManager traceManager;

    public CircuitBreakerOpenScenario(MetricService metricService, TraceManager traceManager) {
        this.metricService = metricService;
        this.traceManager = traceManager;
    }

    @Override
    public String scenarioCode() {
        return ScenarioCode.CIRCUIT_BREAKER_OPEN;
    }

    @Override
    public String scenarioName() {
        return SCENARIO_NAME;
    }

    @Override
    public void execute(String experimentId, Map<String, Object> params) {
        int requestCount = Math.max(0, DownstreamScenarioSupport.readInt(params, "requestCount", DEFAULT_REQUEST_COUNT));
        double failureRatio = DownstreamScenarioSupport.clampRatio(DownstreamScenarioSupport.readDouble(
                params,
                "failureRatio",
                DEFAULT_FAILURE_RATIO
        ));
        double slowCallRatio = DownstreamScenarioSupport.clampRatio(DownstreamScenarioSupport.readDouble(
                params,
                "slowCallRatio",
                DEFAULT_SLOW_CALL_RATIO
        ));
        int slidingWindowSize = Math.max(1, DownstreamScenarioSupport.readInt(
                params,
                "slidingWindowSize",
                DEFAULT_SLIDING_WINDOW_SIZE
        ));
        double failureRateThreshold = DownstreamScenarioSupport.clampRatio(DownstreamScenarioSupport.readDouble(
                params,
                "failureRateThreshold",
                DEFAULT_FAILURE_RATE_THRESHOLD
        ));
        long slowCallThresholdMs = Math.max(1L, DownstreamScenarioSupport.readLong(
                params,
                "slowCallThresholdMs",
                DEFAULT_SLOW_CALL_THRESHOLD_MS
        ));
        long openDurationMs = Math.max(0L, DownstreamScenarioSupport.readLong(
                params,
                "openDurationMs",
                DEFAULT_OPEN_DURATION_MS
        ));
        boolean enableFallback = DownstreamScenarioSupport.readBoolean(
                params,
                "enableFallback",
                DEFAULT_ENABLE_FALLBACK
        );

        SimulationStats stats = simulate(
                requestCount,
                failureRatio,
                slowCallRatio,
                slidingWindowSize,
                failureRateThreshold,
                slowCallThresholdMs,
                openDurationMs,
                enableFallback
        );

        DownstreamScenarioSupport.trace(traceManager, "circuit.evaluate", () -> {
            DownstreamScenarioSupport.trace(traceManager, "circuit.open", () -> {
            });
            DownstreamScenarioSupport.trace(traceManager, "circuit.reject", () -> {
            });
            if (enableFallback) {
                DownstreamScenarioSupport.trace(traceManager, "fallback.execute", () -> {
                });
            }
        });

        recordMetrics(experimentId, stats);
    }

    private SimulationStats simulate(
            int requestCount,
            double failureRatio,
            double slowCallRatio,
            int slidingWindowSize,
            double failureRateThreshold,
            long slowCallThresholdMs,
            long openDurationMs,
            boolean enableFallback
    ) {
        SimulationStats stats = new SimulationStats();
        stats.requestCount = requestCount;
        if (requestCount == 0) {
            return stats;
        }

        int evaluatedCalls = Math.min(requestCount, slidingWindowSize);
        int failureCount = (int) Math.round(evaluatedCalls * failureRatio);
        int slowCallCount = (int) Math.round(evaluatedCalls * slowCallRatio);
        double failureRate = evaluatedCalls == 0 ? 0D : (double) failureCount / evaluatedCalls;
        double slowCallRate = evaluatedCalls == 0 ? 0D : (double) slowCallCount / evaluatedCalls;
        boolean shouldOpen = failureRate >= failureRateThreshold || slowCallRate >= failureRateThreshold;
        int rejectedCount = shouldOpen ? Math.max(0, requestCount - evaluatedCalls) : 0;

        stats.failureCount = failureCount;
        stats.failureRate = failureRate;
        stats.slowCallCount = slowCallCount;
        stats.slowCallRate = slowCallRate;
        stats.openCount = shouldOpen ? 1 : 0;
        stats.halfOpenCount = shouldOpen && openDurationMs > 0 ? 1 : 0;
        stats.rejectedCount = rejectedCount;
        stats.fallbackCount = enableFallback ? rejectedCount : 0;
        stats.skippedDownstreamCallCount = rejectedCount;
        stats.apiErrorCount = enableFallback ? failureCount : failureCount + rejectedCount;
        return stats;
    }

    private void recordMetrics(String experimentId, SimulationStats stats) {
        DownstreamScenarioSupport.record(metricService, experimentId, "circuit.request.count", stats.requestCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "circuit.failure.count", stats.failureCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "circuit.failure.rate", stats.failureRate, "ratio");
        DownstreamScenarioSupport.record(metricService, experimentId, "circuit.slow.call.count", stats.slowCallCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "circuit.slow.call.rate", stats.slowCallRate, "ratio");
        DownstreamScenarioSupport.record(metricService, experimentId, "circuit.open.count", stats.openCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "circuit.half.open.count", stats.halfOpenCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "circuit.rejected.count", stats.rejectedCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "circuit.fallback.count", stats.fallbackCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.call.skipped.count", stats.skippedDownstreamCallCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "api.error.count", stats.apiErrorCount, "count");
    }

    private static class SimulationStats {
        private int requestCount;
        private int failureCount;
        private double failureRate;
        private int slowCallCount;
        private double slowCallRate;
        private int openCount;
        private int halfOpenCount;
        private int rejectedCount;
        private int fallbackCount;
        private int skippedDownstreamCallCount;
        private int apiErrorCount;
    }
}
