package com.faultlab.backend.scenario.db;

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

class DbScenarioTests {

    private final MetricService metricService = mock(MetricService.class);
    private final TraceSpanMapper traceSpanMapper = mock(TraceSpanMapper.class);
    private final TraceManager traceManager = new TraceManager(traceSpanMapper);

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    @Test
    void dbSlowQueryShouldRecordSlowQueryAndFullScanMetrics() {
        TraceContextHolder.set(new TraceContext("trace-1", "root-span", "exp-1"));
        DbSlowQueryScenario scenario = new DbSlowQueryScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of(
                "requestCount", 10,
                "queryMode", "FULL_SCAN",
                "tableSize", 100000,
                "scannedRows", 80000,
                "dbDelayMs", 80
        ));

        assertThat(metricValue("db.slow.query.count")).isEqualTo(10);
        assertThat(metricValue("db.full.scan.count")).isGreaterThan(0);
        assertThat(operationNames()).contains("db.query", "db.scan.rows", "db.explain.check");
    }

    @Test
    void dbSlowQueryShouldReduceSlowQueriesWhenIndexOptimizationEnabled() {
        DbSlowQueryScenario scenario = new DbSlowQueryScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of(
                "requestCount", 10,
                "queryMode", "FULL_SCAN",
                "dbDelayMs", 80,
                "enableIndexOptimization", true
        ));

        assertThat(metricValue("db.index.hit.count")).isEqualTo(10);
        assertThat(metricValue("db.slow.query.count")).isZero();
    }

    @Test
    void dbLockContentionShouldRecordLockWaitMetrics() {
        DbLockContentionScenario scenario = new DbLockContentionScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of(
                "requestCount", 20,
                "concurrency", 5,
                "lockHoldMs", 200,
                "lockWaitTimeoutMs", 100
        ));

        assertThat(metricValue("db.lock.wait.count")).isGreaterThan(0);
        assertThat(metricValue("db.update.timeout.count")).isGreaterThan(0);
        assertThat(operationNames()).contains(
                "db.transaction.begin",
                "db.lock.acquire",
                "db.lock.wait",
                "db.update.row",
                "db.transaction.commit"
        );
    }

    @Test
    void dbLockContentionShouldReduceTimeoutsWithShortTransaction() {
        DbLockContentionScenario scenario = new DbLockContentionScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of(
                "requestCount", 20,
                "concurrency", 5,
                "lockHoldMs", 200,
                "lockWaitTimeoutMs", 100,
                "enableShortTransaction", true
        ));

        assertThat(metricValue("db.lock.wait.count")).isEqualTo(4);
        assertThat(metricValue("db.update.timeout.count")).isZero();
    }

    @Test
    void dbConnectionPoolExhaustionShouldRecordAcquireTimeoutsWhenPoolIsSmall() {
        DbConnectionPoolExhaustionScenario scenario = new DbConnectionPoolExhaustionScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of(
                "requestCount", 30,
                "concurrency", 20,
                "maxPoolSize", 5,
                "queryDelayMs", 200,
                "connectionAcquireTimeoutMs", 50
        ));

        assertThat(metricValue("db.connection.acquire.timeout.count")).isGreaterThan(0);
        assertThat(metricValue("api.error.count")).isGreaterThan(0);
        assertThat(operationNames()).contains(
                "db.connection.acquire",
                "db.connection.wait",
                "db.query.execute",
                "db.connection.release"
        );
    }

    @Test
    void dbConnectionPoolExhaustionShouldReduceTimeoutsWithFastRelease() {
        DbConnectionPoolExhaustionScenario scenario = new DbConnectionPoolExhaustionScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of(
                "requestCount", 30,
                "concurrency", 20,
                "maxPoolSize", 5,
                "queryDelayMs", 200,
                "connectionAcquireTimeoutMs", 50,
                "enableFastRelease", true
        ));

        assertThat(metricValue("db.connection.acquire.timeout.count")).isZero();
        assertThat(metricValue("api.error.count")).isZero();
    }

    private long metricValue(String metricName) {
        ArgumentCaptor<Number> valueCaptor = ArgumentCaptor.forClass(Number.class);
        verify(metricService, atLeastOnce()).recordMetric(
                org.mockito.ArgumentMatchers.eq("exp-1"),
                org.mockito.ArgumentMatchers.eq(metricName),
                valueCaptor.capture(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(DbScenarioSupport.COMPONENT)
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
