package com.faultlab.backend.scenario.cache;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.manager.TraceManager;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CacheBreakdownScenario implements FaultScenario {

    public static final String SCENARIO_NAME = "缓存击穿";
    public static final int DEFAULT_REQUEST_COUNT = 100;
    public static final String DEFAULT_HOT_KEY = "hot:item:1";
    public static final int DEFAULT_CONCURRENCY = 20;
    public static final long DEFAULT_REBUILD_DELAY_MS = 100L;
    public static final boolean DEFAULT_ENABLE_MUTEX = false;
    public static final boolean DEFAULT_ENABLE_LOGICAL_EXPIRE = false;

    private final MetricService metricService;
    private final TraceManager traceManager;

    public CacheBreakdownScenario(MetricService metricService, TraceManager traceManager) {
        this.metricService = metricService;
        this.traceManager = traceManager;
    }

    @Override
    public String scenarioCode() {
        return ScenarioCode.CACHE_BREAKDOWN;
    }

    @Override
    public String scenarioName() {
        return SCENARIO_NAME;
    }

    @Override
    public void execute(String experimentId, Map<String, Object> params) {
        int requestCount = Math.max(0, CacheScenarioSupport.readInt(params, "requestCount", DEFAULT_REQUEST_COUNT));
        String hotKey = CacheScenarioSupport.readString(params, "hotKey", DEFAULT_HOT_KEY);
        int concurrency = Math.max(1, CacheScenarioSupport.readInt(params, "concurrency", DEFAULT_CONCURRENCY));
        long rebuildDelayMs = Math.max(0L, CacheScenarioSupport.readLong(params, "rebuildDelayMs", DEFAULT_REBUILD_DELAY_MS));
        boolean enableMutex = CacheScenarioSupport.readBoolean(params, "enableMutex", DEFAULT_ENABLE_MUTEX);
        boolean enableLogicalExpire = CacheScenarioSupport.readBoolean(params, "enableLogicalExpire", DEFAULT_ENABLE_LOGICAL_EXPIRE);

        String redisKey = CacheScenarioSupport.KEY_PREFIX + hotKey;
        Map<String, String> redis = new HashMap<>();
        Map<String, String> db = Map.of(redisKey, "hot-value");
        redis.remove(redisKey);

        SimulationStats stats = new SimulationStats();
        CacheScenarioSupport.trace(traceManager, "cache.lookup.hot-key", () -> {
            stats.requestCount = requestCount;
            if (enableLogicalExpire) {
                stats.cacheHitCount = requestCount;
                CacheScenarioSupport.trace(traceManager, "cache.rebuild", () -> {
                    redis.put(redisKey, db.get(redisKey));
                    stats.rebuildCount = requestCount == 0 ? 0 : 1;
                    stats.rebuildDurationMs = rebuildDelayMs;
                });
                return;
            }

            stats.hotKeyMissCount = requestCount;
            stats.cacheMissCount = requestCount;
            if (enableMutex) {
                CacheScenarioSupport.trace(traceManager, "lock.acquire", () -> {
                    stats.lockAcquireCount = requestCount == 0 ? 0 : 1;
                    stats.lockFailCount = Math.max(0, requestCount - stats.lockAcquireCount);
                });
                if (requestCount > 0) {
                    CacheScenarioSupport.trace(traceManager, "db.query.hot-key", () -> stats.dbQueryCount = 1);
                    CacheScenarioSupport.trace(traceManager, "cache.rebuild", () -> {
                        redis.put(redisKey, db.get(redisKey));
                        stats.rebuildCount = 1;
                        stats.rebuildDurationMs = rebuildDelayMs;
                    });
                }
            } else {
                CacheScenarioSupport.trace(traceManager, "lock.acquire", () -> {
                });
                CacheScenarioSupport.trace(traceManager, "db.query.hot-key", () -> stats.dbQueryCount = requestCount);
                CacheScenarioSupport.trace(traceManager, "cache.rebuild", () -> {
                    redis.put(redisKey, db.get(redisKey));
                    stats.rebuildCount = requestCount;
                    stats.concurrentRebuildCount = Math.min(requestCount, concurrency);
                    stats.rebuildDurationMs = rebuildDelayMs;
                });
            }
        });

        recordMetrics(experimentId, stats);
    }

    private void recordMetrics(String experimentId, SimulationStats stats) {
        double missRate = stats.requestCount == 0 ? 0D : (double) stats.cacheMissCount / stats.requestCount;
        CacheScenarioSupport.record(metricService, experimentId, "cache.hot.key.request.count", stats.requestCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.hot.key.miss.count", stats.hotKeyMissCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.miss.rate", missRate, "ratio");
        CacheScenarioSupport.record(metricService, experimentId, "cache.db.query.count", stats.dbQueryCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.rebuild.count", stats.rebuildCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.lock.acquire.count", stats.lockAcquireCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.lock.fail.count", stats.lockFailCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.rebuild.duration.ms", stats.rebuildDurationMs, "ms");
        CacheScenarioSupport.record(metricService, experimentId, "cache.concurrent.rebuild.count", stats.concurrentRebuildCount, "count");
    }

    private static class SimulationStats {
        private int requestCount;
        private int cacheHitCount;
        private int hotKeyMissCount;
        private int cacheMissCount;
        private int dbQueryCount;
        private int rebuildCount;
        private int lockAcquireCount;
        private int lockFailCount;
        private long rebuildDurationMs;
        private int concurrentRebuildCount;
    }
}
