package com.faultlab.backend.scenario.db;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.manager.TraceManager;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DbSlowQueryScenario implements FaultScenario {

    public static final String SCENARIO_NAME = "慢 SQL / 全表扫描";
    public static final int DEFAULT_REQUEST_COUNT = 100;
    public static final String DEFAULT_QUERY_MODE = "FULL_SCAN";
    public static final long DEFAULT_TABLE_SIZE = 100000L;
    public static final long DEFAULT_SCANNED_ROWS = 80000L;
    public static final long DEFAULT_DB_DELAY_MS = 80L;
    public static final boolean DEFAULT_ENABLE_INDEX_OPTIMIZATION = false;

    private static final long SLOW_QUERY_THRESHOLD_MS = 50L;

    private final MetricService metricService;
    private final TraceManager traceManager;

    public DbSlowQueryScenario(MetricService metricService, TraceManager traceManager) {
        this.metricService = metricService;
        this.traceManager = traceManager;
    }

    @Override
    public String scenarioCode() {
        return ScenarioCode.DB_SLOW_QUERY;
    }

    @Override
    public String scenarioName() {
        return SCENARIO_NAME;
    }

    @Override
    public void execute(String experimentId, Map<String, Object> params) {
        int requestCount = Math.max(0, DbScenarioSupport.readInt(params, "requestCount", DEFAULT_REQUEST_COUNT));
        String queryMode = normalizeQueryMode(DbScenarioSupport.readString(params, "queryMode", DEFAULT_QUERY_MODE));
        long tableSize = Math.max(1L, DbScenarioSupport.readLong(params, "tableSize", DEFAULT_TABLE_SIZE));
        long scannedRows = clamp(DbScenarioSupport.readLong(params, "scannedRows", DEFAULT_SCANNED_ROWS), 1L, tableSize);
        long dbDelayMs = Math.max(0L, DbScenarioSupport.readLong(params, "dbDelayMs", DEFAULT_DB_DELAY_MS));
        boolean enableIndexOptimization = DbScenarioSupport.readBoolean(
                params,
                "enableIndexOptimization",
                DEFAULT_ENABLE_INDEX_OPTIMIZATION
        );

        boolean indexed = "INDEXED".equals(queryMode) || enableIndexOptimization;
        long effectiveScannedRows = indexed ? Math.max(1L, Math.min(scannedRows, tableSize / 100L)) : scannedRows;
        long avgQueryMs = indexed ? Math.max(1L, dbDelayMs / 4L) : dbDelayMs;
        long maxQueryMs = indexed ? Math.max(avgQueryMs, dbDelayMs / 2L) : dbDelayMs + Math.max(1L, dbDelayMs / 5L);

        SimulationStats stats = new SimulationStats();
        stats.queryCount = requestCount;
        stats.slowQueryCount = avgQueryMs >= SLOW_QUERY_THRESHOLD_MS ? requestCount : 0;
        stats.fullScanCount = indexed ? 0 : requestCount;
        stats.indexHitCount = indexed ? requestCount : 0;
        stats.scannedRows = effectiveScannedRows;
        stats.tableSize = tableSize;
        stats.avgQueryMs = avgQueryMs;
        stats.maxQueryMs = maxQueryMs;
        stats.apiAvgLatencyMs = avgQueryMs + 5L;

        DbScenarioSupport.trace(traceManager, "db.query", () -> {
            DbScenarioSupport.trace(traceManager, "db.scan.rows", () -> {
            });
            DbScenarioSupport.trace(traceManager, indexed ? "db.index.check" : "db.explain.check", () -> {
            });
        });

        recordMetrics(experimentId, stats);
    }

    private void recordMetrics(String experimentId, SimulationStats stats) {
        double slowQueryRate = stats.queryCount == 0 ? 0D : (double) stats.slowQueryCount / stats.queryCount;
        DbScenarioSupport.record(metricService, experimentId, "db.query.count", stats.queryCount, "count");
        DbScenarioSupport.record(metricService, experimentId, "db.slow.query.count", stats.slowQueryCount, "count");
        DbScenarioSupport.record(metricService, experimentId, "db.slow.query.rate", slowQueryRate, "ratio");
        DbScenarioSupport.record(metricService, experimentId, "db.avg.query.ms", stats.avgQueryMs, "ms");
        DbScenarioSupport.record(metricService, experimentId, "db.max.query.ms", stats.maxQueryMs, "ms");
        DbScenarioSupport.record(metricService, experimentId, "db.full.scan.count", stats.fullScanCount, "count");
        DbScenarioSupport.record(metricService, experimentId, "db.index.hit.count", stats.indexHitCount, "count");
        DbScenarioSupport.record(metricService, experimentId, "db.scanned.rows", stats.scannedRows, "rows");
        DbScenarioSupport.record(metricService, experimentId, "db.table.size", stats.tableSize, "rows");
        DbScenarioSupport.record(metricService, experimentId, "api.avg.latency.ms", stats.apiAvgLatencyMs, "ms");
    }

    private String normalizeQueryMode(String queryMode) {
        String normalized = queryMode == null ? DEFAULT_QUERY_MODE : queryMode.trim().toUpperCase(Locale.ROOT);
        if ("INDEXED".equals(normalized)) {
            return "INDEXED";
        }
        return DEFAULT_QUERY_MODE;
    }

    private long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class SimulationStats {
        private int queryCount;
        private int slowQueryCount;
        private long avgQueryMs;
        private long maxQueryMs;
        private int fullScanCount;
        private int indexHitCount;
        private long scannedRows;
        private long tableSize;
        private long apiAvgLatencyMs;
    }
}
