package com.faultlab.backend.scenario.db;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.manager.TraceManager;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

@Component
public class DbConnectionPoolExhaustionScenario implements FaultScenario {

    public static final String SCENARIO_NAME = "数据库连接池耗尽";
    public static final int DEFAULT_REQUEST_COUNT = 100;
    public static final int DEFAULT_CONCURRENCY = 30;
    public static final int DEFAULT_MAX_POOL_SIZE = 10;
    public static final long DEFAULT_QUERY_DELAY_MS = 200L;
    public static final long DEFAULT_CONNECTION_ACQUIRE_TIMEOUT_MS = 50L;
    public static final boolean DEFAULT_ENABLE_FAST_RELEASE = false;

    private final MetricService metricService;
    private final TraceManager traceManager;

    public DbConnectionPoolExhaustionScenario(MetricService metricService, TraceManager traceManager) {
        this.metricService = metricService;
        this.traceManager = traceManager;
    }

    @Override
    public String scenarioCode() {
        return ScenarioCode.DB_CONNECTION_POOL_EXHAUSTION;
    }

    @Override
    public String scenarioName() {
        return SCENARIO_NAME;
    }

    @Override
    public void execute(String experimentId, Map<String, Object> params) {
        int requestCount = Math.max(0, DbScenarioSupport.readInt(params, "requestCount", DEFAULT_REQUEST_COUNT));
        int concurrency = Math.max(1, DbScenarioSupport.readInt(params, "concurrency", DEFAULT_CONCURRENCY));
        int maxPoolSize = Math.max(1, DbScenarioSupport.readInt(params, "maxPoolSize", DEFAULT_MAX_POOL_SIZE));
        long queryDelayMs = Math.max(0L, DbScenarioSupport.readLong(params, "queryDelayMs", DEFAULT_QUERY_DELAY_MS));
        long connectionAcquireTimeoutMs = Math.max(1L, DbScenarioSupport.readLong(
                params,
                "connectionAcquireTimeoutMs",
                DEFAULT_CONNECTION_ACQUIRE_TIMEOUT_MS
        ));
        boolean enableFastRelease = DbScenarioSupport.readBoolean(
                params,
                "enableFastRelease",
                DEFAULT_ENABLE_FAST_RELEASE
        );

        long effectiveQueryDelayMs = enableFastRelease
                ? Math.max(1L, Math.min(queryDelayMs / 4L, connectionAcquireTimeoutMs / 2L))
                : queryDelayMs;
        SimulationStats stats = simulate(requestCount, concurrency, maxPoolSize, effectiveQueryDelayMs, connectionAcquireTimeoutMs);

        Semaphore pool = new Semaphore(maxPoolSize);
        DbScenarioSupport.trace(traceManager, "db.connection.acquire", () -> {
            if (pool.tryAcquire()) {
                try {
                    DbScenarioSupport.trace(traceManager, "db.connection.wait", () -> {
                    });
                    DbScenarioSupport.trace(traceManager, "db.query.execute", () -> {
                    });
                } finally {
                    DbScenarioSupport.trace(traceManager, "db.connection.release", pool::release);
                }
            }
        });

        recordMetrics(experimentId, stats);
    }

    private SimulationStats simulate(
            int requestCount,
            int concurrency,
            int maxPoolSize,
            long effectiveQueryDelayMs,
            long connectionAcquireTimeoutMs
    ) {
        SimulationStats stats = new SimulationStats();
        stats.requestCount = requestCount;
        stats.maxPoolSize = maxPoolSize;
        stats.activeCount = Math.min(Math.min(requestCount, concurrency), maxPoolSize);
        stats.idleCount = Math.max(0, maxPoolSize - stats.activeCount);
        if (requestCount == 0) {
            return stats;
        }

        boolean saturated = concurrency > maxPoolSize && effectiveQueryDelayMs > connectionAcquireTimeoutMs;
        stats.acquireTimeoutCount = saturated ? Math.max(0, requestCount - maxPoolSize) : 0;
        stats.acquireCount = requestCount;
        stats.queryCount = Math.max(0, requestCount - stats.acquireTimeoutCount);
        stats.apiErrorCount = stats.acquireTimeoutCount;
        stats.acquireAvgMs = stats.acquireTimeoutCount == 0 ? Math.min(5L, connectionAcquireTimeoutMs) : connectionAcquireTimeoutMs;
        stats.connectionHoldAvgMs = stats.queryCount == 0 ? 0L : effectiveQueryDelayMs;
        return stats;
    }

    private void recordMetrics(String experimentId, SimulationStats stats) {
        double timeoutRate = stats.acquireCount == 0 ? 0D : (double) stats.acquireTimeoutCount / stats.acquireCount;
        DbScenarioSupport.record(metricService, experimentId, "db.pool.max.size", stats.maxPoolSize, "count");
        DbScenarioSupport.record(metricService, experimentId, "db.pool.active.count", stats.activeCount, "count");
        DbScenarioSupport.record(metricService, experimentId, "db.pool.idle.count", stats.idleCount, "count");
        DbScenarioSupport.record(metricService, experimentId, "db.connection.acquire.count", stats.acquireCount, "count");
        DbScenarioSupport.record(metricService, experimentId, "db.connection.acquire.timeout.count", stats.acquireTimeoutCount, "count");
        DbScenarioSupport.record(metricService, experimentId, "db.connection.acquire.timeout.rate", timeoutRate, "ratio");
        DbScenarioSupport.record(metricService, experimentId, "db.connection.acquire.avg.ms", stats.acquireAvgMs, "ms");
        DbScenarioSupport.record(metricService, experimentId, "db.connection.hold.avg.ms", stats.connectionHoldAvgMs, "ms");
        DbScenarioSupport.record(metricService, experimentId, "db.query.count", stats.queryCount, "count");
        DbScenarioSupport.record(metricService, experimentId, "api.error.count", stats.apiErrorCount, "count");
    }

    private static class SimulationStats {
        private int requestCount;
        private int maxPoolSize;
        private int activeCount;
        private int idleCount;
        private int acquireCount;
        private int acquireTimeoutCount;
        private long acquireAvgMs;
        private long connectionHoldAvgMs;
        private int queryCount;
        private int apiErrorCount;
    }
}
