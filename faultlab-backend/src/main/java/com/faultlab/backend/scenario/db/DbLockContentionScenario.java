package com.faultlab.backend.scenario.db;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.manager.TraceManager;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

@Component
public class DbLockContentionScenario implements FaultScenario {

    public static final String SCENARIO_NAME = "数据库锁竞争 / 长事务阻塞";
    public static final int DEFAULT_REQUEST_COUNT = 50;
    public static final int DEFAULT_CONCURRENCY = 10;
    public static final String DEFAULT_TARGET_ROW_ID = "order:1";
    public static final long DEFAULT_LOCK_HOLD_MS = 200L;
    public static final long DEFAULT_LOCK_WAIT_TIMEOUT_MS = 100L;
    public static final boolean DEFAULT_ENABLE_SHORT_TRANSACTION = false;

    private final MetricService metricService;
    private final TraceManager traceManager;

    public DbLockContentionScenario(MetricService metricService, TraceManager traceManager) {
        this.metricService = metricService;
        this.traceManager = traceManager;
    }

    @Override
    public String scenarioCode() {
        return ScenarioCode.DB_LOCK_CONTENTION;
    }

    @Override
    public String scenarioName() {
        return SCENARIO_NAME;
    }

    @Override
    public void execute(String experimentId, Map<String, Object> params) {
        int requestCount = Math.max(0, DbScenarioSupport.readInt(params, "requestCount", DEFAULT_REQUEST_COUNT));
        int concurrency = Math.max(1, DbScenarioSupport.readInt(params, "concurrency", DEFAULT_CONCURRENCY));
        String targetRowId = DbScenarioSupport.readString(params, "targetRowId", DEFAULT_TARGET_ROW_ID);
        long lockHoldMs = Math.max(0L, DbScenarioSupport.readLong(params, "lockHoldMs", DEFAULT_LOCK_HOLD_MS));
        long lockWaitTimeoutMs = Math.max(1L, DbScenarioSupport.readLong(
                params,
                "lockWaitTimeoutMs",
                DEFAULT_LOCK_WAIT_TIMEOUT_MS
        ));
        boolean enableShortTransaction = DbScenarioSupport.readBoolean(
                params,
                "enableShortTransaction",
                DEFAULT_ENABLE_SHORT_TRANSACTION
        );

        long effectiveHoldMs = enableShortTransaction ? Math.max(1L, Math.min(lockHoldMs / 4L, lockWaitTimeoutMs / 2L)) : lockHoldMs;
        SimulationStats stats = simulate(requestCount, concurrency, effectiveHoldMs, lockWaitTimeoutMs, enableShortTransaction);

        ReentrantLock rowLock = new ReentrantLock();
        DbScenarioSupport.trace(traceManager, "db.transaction.begin", () -> {
            DbScenarioSupport.trace(traceManager, "db.lock.acquire", () -> {
                rowLock.lock();
                rowLock.unlock();
            });
            DbScenarioSupport.trace(traceManager, "db.lock.wait", () -> {
            });
            DbScenarioSupport.trace(traceManager, "db.update.row", () -> {
            });
            DbScenarioSupport.trace(traceManager, "db.transaction.commit", () -> {
            });
        });

        recordMetrics(experimentId, stats);
    }

    private SimulationStats simulate(
            int requestCount,
            int concurrency,
            long effectiveHoldMs,
            long lockWaitTimeoutMs,
            boolean enableShortTransaction
    ) {
        SimulationStats stats = new SimulationStats();
        stats.requestCount = requestCount;
        stats.transactionActiveCount = Math.min(requestCount, concurrency);
        if (requestCount == 0) {
            return stats;
        }

        if (enableShortTransaction) {
            stats.lockWaitCount = Math.max(0, requestCount / Math.max(2, concurrency));
            stats.updateTimeoutCount = 0;
            stats.lockWaitMs = stats.lockWaitCount * Math.min(effectiveHoldMs, lockWaitTimeoutMs);
            stats.longTransactionCount = 0;
        } else {
            stats.lockWaitCount = Math.max(0, requestCount - 1);
            stats.updateTimeoutCount = effectiveHoldMs > lockWaitTimeoutMs ? Math.max(0, requestCount - 1) : 0;
            stats.lockWaitMs = stats.lockWaitCount * Math.min(effectiveHoldMs, lockWaitTimeoutMs);
            stats.longTransactionCount = effectiveHoldMs >= lockWaitTimeoutMs ? Math.max(1, requestCount / concurrency) : 0;
        }
        stats.updateSuccessCount = Math.max(0, requestCount - stats.updateTimeoutCount);
        stats.apiTimeoutCount = stats.updateTimeoutCount;
        stats.avgLockWaitMs = stats.lockWaitCount == 0 ? 0D : (double) stats.lockWaitMs / stats.lockWaitCount;
        stats.maxLockWaitMs = stats.lockWaitCount == 0 ? 0L : Math.min(effectiveHoldMs, lockWaitTimeoutMs);
        return stats;
    }

    private void recordMetrics(String experimentId, SimulationStats stats) {
        double lockWaitRate = stats.requestCount == 0 ? 0D : (double) stats.lockWaitCount / stats.requestCount;
        DbScenarioSupport.record(metricService, experimentId, "db.lock.request.count", stats.requestCount, "count");
        DbScenarioSupport.record(metricService, experimentId, "db.lock.wait.count", stats.lockWaitCount, "count");
        DbScenarioSupport.record(metricService, experimentId, "db.lock.wait.rate", lockWaitRate, "ratio");
        DbScenarioSupport.record(metricService, experimentId, "db.lock.wait.ms", stats.lockWaitMs, "ms");
        DbScenarioSupport.record(metricService, experimentId, "db.avg.lock.wait.ms", stats.avgLockWaitMs, "ms");
        DbScenarioSupport.record(metricService, experimentId, "db.max.lock.wait.ms", stats.maxLockWaitMs, "ms");
        DbScenarioSupport.record(metricService, experimentId, "db.long.transaction.count", stats.longTransactionCount, "count");
        DbScenarioSupport.record(metricService, experimentId, "db.transaction.active.count", stats.transactionActiveCount, "count");
        DbScenarioSupport.record(metricService, experimentId, "db.update.success.count", stats.updateSuccessCount, "count");
        DbScenarioSupport.record(metricService, experimentId, "db.update.timeout.count", stats.updateTimeoutCount, "count");
        DbScenarioSupport.record(metricService, experimentId, "api.timeout.count", stats.apiTimeoutCount, "count");
    }

    private static class SimulationStats {
        private int requestCount;
        private int lockWaitCount;
        private long lockWaitMs;
        private double avgLockWaitMs;
        private long maxLockWaitMs;
        private int longTransactionCount;
        private int transactionActiveCount;
        private int updateSuccessCount;
        private int updateTimeoutCount;
        private int apiTimeoutCount;
    }
}
