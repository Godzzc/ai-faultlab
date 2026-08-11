package com.faultlab.backend.scenario.cache;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.manager.TraceManager;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CacheAvalancheScenario implements FaultScenario {

    public static final String SCENARIO_NAME = "缓存雪崩";
    public static final int DEFAULT_KEY_COUNT = 50;
    public static final int DEFAULT_REQUEST_COUNT = 200;
    public static final boolean DEFAULT_SAME_TTL = true;
    public static final boolean DEFAULT_ENABLE_TTL_JITTER = false;
    public static final boolean DEFAULT_SIMULATE_REDIS_DOWN = false;
    public static final boolean DEFAULT_ENABLE_FALLBACK = false;
    public static final long DEFAULT_DB_DELAY_MS = 20L;

    private final MetricService metricService;
    private final TraceManager traceManager;

    public CacheAvalancheScenario(MetricService metricService, TraceManager traceManager) {
        this.metricService = metricService;
        this.traceManager = traceManager;
    }

    @Override
    public String scenarioCode() {
        return ScenarioCode.CACHE_AVALANCHE;
    }

    @Override
    public String scenarioName() {
        return SCENARIO_NAME;
    }

    @Override
    public void execute(String experimentId, Map<String, Object> params) {
        int keyCount = Math.max(1, CacheScenarioSupport.readInt(params, "keyCount", DEFAULT_KEY_COUNT));
        int requestCount = Math.max(0, CacheScenarioSupport.readInt(params, "requestCount", DEFAULT_REQUEST_COUNT));
        boolean sameTtl = CacheScenarioSupport.readBoolean(params, "sameTtl", DEFAULT_SAME_TTL);
        boolean enableTtlJitter = CacheScenarioSupport.readBoolean(params, "enableTtlJitter", DEFAULT_ENABLE_TTL_JITTER);
        boolean simulateRedisDown = CacheScenarioSupport.readBoolean(params, "simulateRedisDown", DEFAULT_SIMULATE_REDIS_DOWN);
        boolean enableFallback = CacheScenarioSupport.readBoolean(params, "enableFallback", DEFAULT_ENABLE_FALLBACK);
        long dbDelayMs = Math.max(0L, CacheScenarioSupport.readLong(params, "dbDelayMs", DEFAULT_DB_DELAY_MS));

        Map<String, String> redis = new HashMap<>();
        Map<String, String> db = new HashMap<>();
        for (int index = 0; index < keyCount; index++) {
            String key = CacheScenarioSupport.KEY_PREFIX + "avalanche:item:" + index;
            redis.put(key, "cache-value-" + index);
            db.put(key, "db-value-" + index);
        }

        int expiredKeyCount = expiredKeyCount(keyCount, sameTtl, enableTtlJitter, simulateRedisDown);
        for (int index = 0; index < expiredKeyCount; index++) {
            redis.remove(CacheScenarioSupport.KEY_PREFIX + "avalanche:item:" + index);
        }

        SimulationStats stats = new SimulationStats();
        stats.keyCount = keyCount;
        stats.expiredKeyCount = expiredKeyCount;
        CacheScenarioSupport.trace(traceManager, "cache.batch.lookup", () -> {
            for (int index = 0; index < requestCount; index++) {
                String key = CacheScenarioSupport.KEY_PREFIX + "avalanche:item:" + (index % keyCount);
                stats.requestCount++;
                if (simulateRedisDown) {
                    stats.cacheUnavailableCount++;
                    if (enableFallback) {
                        CacheScenarioSupport.trace(traceManager, "fallback.local-cache", () -> stats.fallbackCount++);
                    } else {
                        stats.requestErrorCount++;
                    }
                    continue;
                }
                if (redis.containsKey(key)) {
                    stats.cacheHitCount++;
                    continue;
                }
                stats.cacheMissCount++;
                CacheScenarioSupport.trace(traceManager, "db.batch.query", () -> {
                    stats.dbQueryCount++;
                    if (dbDelayMs >= 20L) {
                        stats.dbSlowQueryCount++;
                    }
                });
            }
        });
        if (simulateRedisDown) {
            CacheScenarioSupport.trace(traceManager, "redis.unavailable", () -> {
            });
        }

        recordMetrics(experimentId, stats);
    }

    private int expiredKeyCount(int keyCount, boolean sameTtl, boolean enableTtlJitter, boolean simulateRedisDown) {
        if (simulateRedisDown) {
            return keyCount;
        }
        if (sameTtl && !enableTtlJitter) {
            return keyCount;
        }
        if (enableTtlJitter) {
            return Math.max(1, keyCount / 5);
        }
        return Math.max(1, keyCount / 2);
    }

    private void recordMetrics(String experimentId, SimulationStats stats) {
        double missRate = stats.requestCount == 0 ? 0D : (double) stats.cacheMissCount / stats.requestCount;
        CacheScenarioSupport.record(metricService, experimentId, "cache.key.count", stats.keyCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.expired.key.count", stats.expiredKeyCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.unavailable.count", stats.cacheUnavailableCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.request.count", stats.requestCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.hit.count", stats.cacheHitCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.miss.count", stats.cacheMissCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.miss.rate", missRate, "ratio");
        CacheScenarioSupport.record(metricService, experimentId, "cache.db.query.count", stats.dbQueryCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.db.slow.query.count", stats.dbSlowQueryCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.fallback.count", stats.fallbackCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.request.error.count", stats.requestErrorCount, "count");
    }

    private static class SimulationStats {
        private int keyCount;
        private int expiredKeyCount;
        private int cacheUnavailableCount;
        private int requestCount;
        private int cacheHitCount;
        private int cacheMissCount;
        private int dbQueryCount;
        private int dbSlowQueryCount;
        private int fallbackCount;
        private int requestErrorCount;
    }
}
