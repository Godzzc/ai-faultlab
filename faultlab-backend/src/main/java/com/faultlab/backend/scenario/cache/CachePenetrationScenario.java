package com.faultlab.backend.scenario.cache;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.manager.TraceManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CachePenetrationScenario implements FaultScenario {

    public static final String SCENARIO_NAME = "缓存穿透";
    public static final int DEFAULT_REQUEST_COUNT = 100;
    public static final double DEFAULT_INVALID_KEY_RATIO = 0.8D;
    public static final boolean DEFAULT_ENABLE_NULL_CACHE = false;
    public static final boolean DEFAULT_ENABLE_BLOOM_FILTER = false;
    public static final long DEFAULT_DB_DELAY_MS = 20L;

    private static final String NULL_VALUE = "__NULL__";

    private final MetricService metricService;
    private final TraceManager traceManager;

    public CachePenetrationScenario(MetricService metricService, TraceManager traceManager) {
        this.metricService = metricService;
        this.traceManager = traceManager;
    }

    @Override
    public String scenarioCode() {
        return ScenarioCode.CACHE_PENETRATION;
    }

    @Override
    public String scenarioName() {
        return SCENARIO_NAME;
    }

    @Override
    public void execute(String experimentId, Map<String, Object> params) {
        int requestCount = Math.max(0, CacheScenarioSupport.readInt(params, "requestCount", DEFAULT_REQUEST_COUNT));
        double invalidKeyRatio = clamp(CacheScenarioSupport.readDouble(params, "invalidKeyRatio", DEFAULT_INVALID_KEY_RATIO), 0D, 1D);
        boolean enableNullCache = CacheScenarioSupport.readBoolean(params, "enableNullCache", DEFAULT_ENABLE_NULL_CACHE);
        boolean enableBloomFilter = CacheScenarioSupport.readBoolean(params, "enableBloomFilter", DEFAULT_ENABLE_BLOOM_FILTER);
        long dbDelayMs = Math.max(0L, CacheScenarioSupport.readLong(params, "dbDelayMs", DEFAULT_DB_DELAY_MS));

        Set<String> legalKeys = Set.of(prefixed("item:1"), prefixed("item:2"), prefixed("item:3"));
        Map<String, String> redis = new HashMap<>();
        Map<String, String> db = new HashMap<>();
        for (String key : legalKeys) {
            db.put(key, "value:" + key);
        }
        redis.put(prefixed("item:1"), db.get(prefixed("item:1")));

        SimulationStats stats = new SimulationStats();
        CacheScenarioSupport.trace(traceManager, "cache.lookup", () -> {
            for (int index = 0; index < requestCount; index++) {
                String key = keyFor(index, requestCount, invalidKeyRatio);
                stats.requestCount++;
                if (enableBloomFilter && !legalKeys.contains(key)) {
                    stats.bloomRejectCount++;
                    continue;
                }
                String cachedValue = redis.get(key);
                if (cachedValue != null) {
                    stats.cacheHitCount++;
                    continue;
                }
                stats.cacheMissCount++;
                CacheScenarioSupport.trace(traceManager, "db.query", () -> queryDb(db, key, stats));
                if (!db.containsKey(key)) {
                    stats.invalidKeyCount++;
                    if (enableNullCache) {
                        CacheScenarioSupport.trace(traceManager, "mitigation.null-cache", () -> {
                            redis.put(key, NULL_VALUE);
                            stats.nullCacheWriteCount++;
                        });
                    }
                } else {
                    redis.put(key, db.get(key));
                }
            }
        });
        if (enableBloomFilter) {
            CacheScenarioSupport.trace(traceManager, "mitigation.bloom-filter", () -> {
            });
        }

        recordMetrics(experimentId, stats, dbDelayMs);
    }

    private void queryDb(Map<String, String> db, String key, SimulationStats stats) {
        stats.dbQueryCount++;
    }

    private void recordMetrics(String experimentId, SimulationStats stats, long dbDelayMs) {
        double missRate = stats.requestCount == 0 ? 0D : (double) stats.cacheMissCount / stats.requestCount;
        double dbQueryRate = stats.requestCount == 0 ? 0D : (double) stats.dbQueryCount / stats.requestCount;
        double avgDbQueryMs = stats.dbQueryCount == 0 ? 0D : dbDelayMs;
        CacheScenarioSupport.record(metricService, experimentId, "cache.request.count", stats.requestCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.hit.count", stats.cacheHitCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.miss.count", stats.cacheMissCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.miss.rate", missRate, "ratio");
        CacheScenarioSupport.record(metricService, experimentId, "cache.db.query.count", stats.dbQueryCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.db.query.rate", dbQueryRate, "ratio");
        CacheScenarioSupport.record(metricService, experimentId, "cache.invalid.key.count", stats.invalidKeyCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.null.cache.write.count", stats.nullCacheWriteCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.bloom.reject.count", stats.bloomRejectCount, "count");
        CacheScenarioSupport.record(metricService, experimentId, "cache.avg.db.query.ms", avgDbQueryMs, "ms");
    }

    private String keyFor(int index, int requestCount, double invalidKeyRatio) {
        int invalidCount = (int) Math.round(requestCount * invalidKeyRatio);
        if (index < invalidCount) {
            return prefixed("item:invalid:" + index);
        }
        int legalIndex = (index % 3) + 1;
        return prefixed("item:" + legalIndex);
    }

    private String prefixed(String key) {
        return CacheScenarioSupport.KEY_PREFIX + key;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class SimulationStats {
        private int requestCount;
        private int cacheHitCount;
        private int cacheMissCount;
        private int dbQueryCount;
        private int invalidKeyCount;
        private int nullCacheWriteCount;
        private int bloomRejectCount;
    }
}
