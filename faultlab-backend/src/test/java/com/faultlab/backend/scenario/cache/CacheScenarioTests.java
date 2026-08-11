package com.faultlab.backend.scenario.cache;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.trace.context.TraceContext;
import com.faultlab.backend.trace.context.TraceContextHolder;
import com.faultlab.backend.trace.entity.TraceSpan;
import com.faultlab.backend.trace.mapper.TraceSpanMapper;
import com.faultlab.backend.trace.manager.TraceManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CacheScenarioTests {

    private final MetricService metricService = mock(MetricService.class);
    private final TraceSpanMapper traceSpanMapper = mock(TraceSpanMapper.class);
    private final TraceManager traceManager = new TraceManager(traceSpanMapper);

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    @Test
    void cachePenetrationShouldRecordDbQueryCount() {
        TraceContextHolder.set(new TraceContext("trace-1", "root-span", "exp-1"));
        CachePenetrationScenario scenario = new CachePenetrationScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of("requestCount", 10, "invalidKeyRatio", 0.8));

        assertThat(metricValue("cache.db.query.count")).isGreaterThan(0);
        assertThat(operationNames()).contains("cache.lookup", "db.query");
    }

    @Test
    void cachePenetrationShouldWriteNullCacheWhenEnabled() {
        CachePenetrationScenario scenario = new CachePenetrationScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of("requestCount", 10, "invalidKeyRatio", 0.8, "enableNullCache", true));

        assertThat(metricValue("cache.null.cache.write.count")).isGreaterThan(0);
    }

    @Test
    void cachePenetrationShouldRejectByBloomFilterWhenEnabled() {
        CachePenetrationScenario scenario = new CachePenetrationScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of("requestCount", 10, "invalidKeyRatio", 0.8, "enableBloomFilter", true));

        assertThat(metricValue("cache.bloom.reject.count")).isGreaterThan(0);
    }

    @Test
    void cacheBreakdownShouldRecordHighRebuildCountWithoutMutex() {
        CacheBreakdownScenario scenario = new CacheBreakdownScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of("requestCount", 10, "concurrency", 5, "enableMutex", false));

        assertThat(metricValue("cache.rebuild.count")).isEqualTo(10);
        assertThat(metricValue("cache.concurrent.rebuild.count")).isEqualTo(5);
    }

    @Test
    void cacheBreakdownShouldReduceRebuildCountWithMutex() {
        CacheBreakdownScenario scenario = new CacheBreakdownScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of("requestCount", 10, "concurrency", 5, "enableMutex", true));

        assertThat(metricValue("cache.rebuild.count")).isEqualTo(1);
        assertThat(metricValue("cache.lock.acquire.count")).isEqualTo(1);
        assertThat(metricValue("cache.lock.fail.count")).isEqualTo(9);
    }

    @Test
    void cacheAvalancheShouldRecordExpiredKeysWhenSameTtl() {
        CacheAvalancheScenario scenario = new CacheAvalancheScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of("keyCount", 10, "requestCount", 20, "sameTtl", true));

        assertThat(metricValue("cache.expired.key.count")).isGreaterThan(0);
        assertThat(metricValue("cache.db.query.count")).isGreaterThan(0);
    }

    @Test
    void cacheAvalancheShouldRecordRedisUnavailable() {
        CacheAvalancheScenario scenario = new CacheAvalancheScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of("keyCount", 10, "requestCount", 20, "simulateRedisDown", true));

        assertThat(metricValue("cache.unavailable.count")).isGreaterThan(0);
        assertThat(operationNames()).contains("redis.unavailable");
    }

    @Test
    void cacheAvalancheShouldRecordFallbackWhenEnabled() {
        CacheAvalancheScenario scenario = new CacheAvalancheScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of("keyCount", 10, "requestCount", 20, "simulateRedisDown", true, "enableFallback", true));

        assertThat(metricValue("cache.fallback.count")).isGreaterThan(0);
        assertThat(metricValue("cache.request.error.count")).isZero();
    }

    private long metricValue(String metricName) {
        ArgumentCaptor<Number> valueCaptor = ArgumentCaptor.forClass(Number.class);
        verify(metricService, atLeastOnce()).recordMetric(
                org.mockito.ArgumentMatchers.eq("exp-1"),
                org.mockito.ArgumentMatchers.eq(metricName),
                valueCaptor.capture(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(CacheScenarioSupport.COMPONENT)
        );
        Number value = valueCaptor.getAllValues().get(valueCaptor.getAllValues().size() - 1);
        return value.longValue();
    }

    private List<String> operationNames() {
        ArgumentCaptor<TraceSpan> spanCaptor = ArgumentCaptor.forClass(TraceSpan.class);
        verify(traceSpanMapper, atLeastOnce()).insert(spanCaptor.capture());
        return spanCaptor.getAllValues().stream()
                .map(TraceSpan::getOperationName)
                .toList();
    }
}
