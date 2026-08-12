package com.faultlab.backend.scenario.downstream;

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

class DownstreamScenarioTests {

    private final MetricService metricService = mock(MetricService.class);
    private final TraceSpanMapper traceSpanMapper = mock(TraceSpanMapper.class);
    private final TraceManager traceManager = new TraceManager(traceSpanMapper);

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    @Test
    void downstreamTimeoutShouldRecordTimeoutMetrics() {
        TraceContextHolder.set(new TraceContext("trace-1", "root-span", "exp-1"));
        DownstreamTimeoutScenario scenario = new DownstreamTimeoutScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of(
                "requestCount", 20,
                "concurrency", 5,
                "downstreamDelayMs", 300,
                "timeoutMs", 100,
                "timeoutRatio", 0.8,
                "enableFallback", false
        ));

        assertThat(metricValue("downstream.timeout.count")).isGreaterThan(0);
        assertThat(metricValue("api.error.count")).isGreaterThan(0);
        assertThat(operationNames()).contains("downstream.call", "downstream.timeout");
    }

    @Test
    void downstreamTimeoutShouldRecordFallbackWhenEnabled() {
        DownstreamTimeoutScenario scenario = new DownstreamTimeoutScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of(
                "requestCount", 20,
                "downstreamDelayMs", 300,
                "timeoutMs", 100,
                "timeoutRatio", 0.8,
                "enableFallback", true
        ));

        assertThat(metricValue("downstream.fallback.count")).isGreaterThan(0);
        assertThat(metricValue("api.error.count")).isZero();
        assertThat(operationNames()).contains("fallback.execute");
    }

    @Test
    void retryStormShouldRecordRetryAmplification() {
        RetryStormScenario scenario = new RetryStormScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of(
                "requestCount", 20,
                "concurrency", 5,
                "failureRatio", 0.7,
                "maxRetries", 3
        ));

        assertThat(metricValue("downstream.retry.count")).isGreaterThan(0);
        assertThat(metricDoubleValue("downstream.retry.amplification.factor")).isGreaterThan(1D);
        assertThat(operationNames()).contains("downstream.call", "retry.attempt", "retry.exhausted");
    }

    @Test
    void retryStormShouldReduceAmplificationWhenRetryLimitEnabled() {
        RetryStormScenario scenario = new RetryStormScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of(
                "requestCount", 20,
                "failureRatio", 0.7,
                "maxRetries", 3,
                "enableRetryLimit", true
        ));

        assertThat(metricValue("downstream.retry.count")).isEqualTo(14);
        assertThat(metricDoubleValue("downstream.retry.amplification.factor")).isLessThan(2D);
    }

    @Test
    void circuitBreakerOpenShouldRecordOpenAndRejectedMetrics() {
        CircuitBreakerOpenScenario scenario = new CircuitBreakerOpenScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of(
                "requestCount", 50,
                "failureRatio", 0.8,
                "slowCallRatio", 0.5,
                "slidingWindowSize", 10,
                "failureRateThreshold", 0.5,
                "enableFallback", false
        ));

        assertThat(metricValue("circuit.open.count")).isGreaterThan(0);
        assertThat(metricValue("circuit.rejected.count")).isGreaterThan(0);
        assertThat(metricValue("downstream.call.skipped.count")).isGreaterThan(0);
        assertThat(operationNames()).contains("circuit.evaluate", "circuit.open", "circuit.reject");
    }

    @Test
    void circuitBreakerOpenShouldRecordFallbackWhenEnabled() {
        CircuitBreakerOpenScenario scenario = new CircuitBreakerOpenScenario(metricService, traceManager);

        scenario.execute("exp-1", Map.of(
                "requestCount", 50,
                "failureRatio", 0.8,
                "slidingWindowSize", 10,
                "failureRateThreshold", 0.5,
                "enableFallback", true
        ));

        assertThat(metricValue("circuit.fallback.count")).isGreaterThan(0);
        assertThat(operationNames()).contains("fallback.execute");
    }

    private long metricValue(String metricName) {
        Number value = capturedMetricValue(metricName);
        return value.longValue();
    }

    private double metricDoubleValue(String metricName) {
        Number value = capturedMetricValue(metricName);
        return value.doubleValue();
    }

    private Number capturedMetricValue(String metricName) {
        ArgumentCaptor<Number> valueCaptor = ArgumentCaptor.forClass(Number.class);
        verify(metricService, atLeastOnce()).recordMetric(
                org.mockito.ArgumentMatchers.eq("exp-1"),
                org.mockito.ArgumentMatchers.eq(metricName),
                valueCaptor.capture(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(DownstreamScenarioSupport.COMPONENT)
        );
        return valueCaptor.getAllValues().get(valueCaptor.getAllValues().size() - 1);
    }

    private List<String> operationNames() {
        ArgumentCaptor<TraceSpan> spanCaptor = ArgumentCaptor.forClass(TraceSpan.class);
        verify(traceSpanMapper, atLeastOnce()).insert(spanCaptor.capture());
        return spanCaptor.getAllValues().stream()
                .map(TraceSpan::getOperationName)
                .toList();
    }
}
