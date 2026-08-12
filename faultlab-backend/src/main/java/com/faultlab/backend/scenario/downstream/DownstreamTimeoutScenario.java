package com.faultlab.backend.scenario.downstream;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.manager.TraceManager;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DownstreamTimeoutScenario implements FaultScenario {

    public static final String SCENARIO_NAME = "下游接口超时";
    public static final int DEFAULT_REQUEST_COUNT = 100;
    public static final int DEFAULT_CONCURRENCY = 20;
    public static final long DEFAULT_DOWNSTREAM_DELAY_MS = 300L;
    public static final long DEFAULT_TIMEOUT_MS = 100L;
    public static final double DEFAULT_TIMEOUT_RATIO = 0.8D;
    public static final boolean DEFAULT_ENABLE_FALLBACK = false;
    public static final long DEFAULT_FALLBACK_DELAY_MS = 10L;

    private final MetricService metricService;
    private final TraceManager traceManager;

    public DownstreamTimeoutScenario(MetricService metricService, TraceManager traceManager) {
        this.metricService = metricService;
        this.traceManager = traceManager;
    }

    @Override
    public String scenarioCode() {
        return ScenarioCode.DOWNSTREAM_TIMEOUT;
    }

    @Override
    public String scenarioName() {
        return SCENARIO_NAME;
    }

    @Override
    public void execute(String experimentId, Map<String, Object> params) {
        int requestCount = Math.max(0, DownstreamScenarioSupport.readInt(params, "requestCount", DEFAULT_REQUEST_COUNT));
        int concurrency = Math.max(1, DownstreamScenarioSupport.readInt(params, "concurrency", DEFAULT_CONCURRENCY));
        long downstreamDelayMs = Math.max(0L, DownstreamScenarioSupport.readLong(
                params,
                "downstreamDelayMs",
                DEFAULT_DOWNSTREAM_DELAY_MS
        ));
        long timeoutMs = Math.max(1L, DownstreamScenarioSupport.readLong(params, "timeoutMs", DEFAULT_TIMEOUT_MS));
        double timeoutRatio = DownstreamScenarioSupport.clampRatio(DownstreamScenarioSupport.readDouble(
                params,
                "timeoutRatio",
                DEFAULT_TIMEOUT_RATIO
        ));
        boolean enableFallback = DownstreamScenarioSupport.readBoolean(
                params,
                "enableFallback",
                DEFAULT_ENABLE_FALLBACK
        );
        long fallbackDelayMs = Math.max(0L, DownstreamScenarioSupport.readLong(
                params,
                "fallbackDelayMs",
                DEFAULT_FALLBACK_DELAY_MS
        ));

        SimulationStats stats = simulate(
                requestCount,
                concurrency,
                downstreamDelayMs,
                timeoutMs,
                timeoutRatio,
                enableFallback,
                fallbackDelayMs
        );

        DownstreamScenarioSupport.trace(traceManager, "downstream.call", () -> {
            DownstreamScenarioSupport.trace(traceManager, "downstream.timeout", () -> {
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
            int concurrency,
            long downstreamDelayMs,
            long timeoutMs,
            double timeoutRatio,
            boolean enableFallback,
            long fallbackDelayMs
    ) {
        SimulationStats stats = new SimulationStats();
        stats.requestCount = requestCount;
        if (requestCount == 0) {
            return stats;
        }

        int slowCallCount = (int) Math.round(requestCount * timeoutRatio);
        int fastCallCount = Math.max(0, requestCount - slowCallCount);
        int timeoutCount = downstreamDelayMs > timeoutMs ? slowCallCount : 0;
        long fastLatencyMs = Math.max(1L, Math.min(timeoutMs / 2L, downstreamDelayMs));
        long totalLatencyMs = slowCallCount * downstreamDelayMs + fastCallCount * fastLatencyMs;

        stats.timeoutCount = timeoutCount;
        stats.slowCallCount = slowCallCount;
        stats.fallbackCount = enableFallback ? timeoutCount : 0;
        stats.apiErrorCount = enableFallback ? 0 : timeoutCount;
        stats.apiSuccessCount = Math.max(0, requestCount - stats.apiErrorCount);
        stats.avgLatencyMs = totalLatencyMs / requestCount;
        stats.maxLatencyMs = slowCallCount > 0 ? downstreamDelayMs : fastLatencyMs;
        stats.apiAvgLatencyMs = stats.avgLatencyMs + (stats.fallbackCount == 0 ? 0L : fallbackDelayMs);
        return stats;
    }

    private void recordMetrics(String experimentId, SimulationStats stats) {
        double timeoutRate = stats.requestCount == 0 ? 0D : (double) stats.timeoutCount / stats.requestCount;
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.request.count", stats.requestCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.timeout.count", stats.timeoutCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.timeout.rate", timeoutRate, "ratio");
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.avg.latency.ms", stats.avgLatencyMs, "ms");
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.max.latency.ms", stats.maxLatencyMs, "ms");
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.slow.call.count", stats.slowCallCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "downstream.fallback.count", stats.fallbackCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "api.avg.latency.ms", stats.apiAvgLatencyMs, "ms");
        DownstreamScenarioSupport.record(metricService, experimentId, "api.error.count", stats.apiErrorCount, "count");
        DownstreamScenarioSupport.record(metricService, experimentId, "api.success.count", stats.apiSuccessCount, "count");
    }

    private static class SimulationStats {
        private int requestCount;
        private int timeoutCount;
        private long avgLatencyMs;
        private long maxLatencyMs;
        private int slowCallCount;
        private int fallbackCount;
        private long apiAvgLatencyMs;
        private int apiErrorCount;
        private int apiSuccessCount;
    }
}
