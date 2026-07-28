package com.faultlab.backend.scenario.idempotency;

import com.faultlab.backend.metric.service.MetricService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencySimulatorServiceTests {

    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final MetricService metricService = mock(MetricService.class);
    private final IdempotencyCheckService idempotencyCheckService = new IdempotencyCheckService(stringRedisTemplate);
    private final IdempotencySimulatorService simulatorService = new IdempotencySimulatorService(idempotencyCheckService, metricService);

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void shouldSimulateDuplicateRequests() {
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true, false);
        when(valueOperations.get("idempotency:exp-1:idem-1:hash")).thenReturn("hash-a");

        IdempotencySimulationResult result = simulatorService.simulate("exp-1", List.of(
                new IdempotencySimulatedRequest("idem-1", "hash-a", 0),
                new IdempotencySimulatedRequest("idem-1", "hash-a", 1)
        ), 0L);

        assertThat(result.getAcceptedCount()).isEqualTo(1);
        assertThat(result.getRejectedDuplicateCount()).isEqualTo(1);
        assertThat(result.getHashMismatchCount()).isZero();
        verify(metricService).recordMetric("exp-1", "duplicateCount", 1, "count", "Idempotency");
        verify(metricService).recordMetric("exp-1", "redisSetNxFailCount", 1, "count", "Idempotency");
    }

    @Test
    void shouldSimulateHashMismatchConflict() {
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true, false);
        when(valueOperations.get("idempotency:exp-1:idem-1:hash")).thenReturn("hash-a");

        IdempotencySimulationResult result = simulatorService.simulate("exp-1", List.of(
                new IdempotencySimulatedRequest("idem-1", "hash-a", 0),
                new IdempotencySimulatedRequest("idem-1", "hash-b", 1)
        ), 0L);

        assertThat(result.getAcceptedCount()).isEqualTo(1);
        assertThat(result.getRejectedDuplicateCount()).isZero();
        assertThat(result.getHashMismatchCount()).isEqualTo(1);
        verify(metricService).recordMetric("exp-1", "conflictCount", 1, "count", "Idempotency");
        verify(metricService).recordMetric("exp-1", "hashMismatchCount", 1, "count", "Idempotency");
    }

    @Test
    void shouldRecordIdempotencyMetrics() {
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);

        simulatorService.simulate("exp-1", List.of(new IdempotencySimulatedRequest("idem-1", "hash-a", 0)), 0L);

        verify(metricService).recordMetric("exp-1", "requestCount", 1, "count", "Idempotency");
        verify(metricService).recordMetric("exp-1", "acceptedCount", 1, "count", "Idempotency");
        verify(metricService).recordMetric("exp-1", "rejectedDuplicateCount", 0, "count", "Idempotency");
        verify(metricService).recordMetric("exp-1", "hashMismatchCount", 0, "count", "Idempotency");
        verify(metricService).recordMetric("exp-1", "redisSetNxSuccessCount", 1, "count", "Idempotency");
        verify(metricService).recordMetric("exp-1", "redisSetNxFailCount", 0, "count", "Idempotency");
        verify(metricService).recordMetric("exp-1", "redisErrorCount", 0, "count", "Idempotency");
        verify(metricService).recordMetric("exp-1", "processingDelayMs", 0L, "ms", "Idempotency");
        verify(metricService).recordMetric(eq("exp-1"), eq("avgCheckDurationMs"), any(Number.class), eq("ms"), eq("Idempotency"));
    }

    @Test
    void shouldHandleRedisExceptionWithoutCrashingStartApi() {
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenThrow(new RuntimeException("redis unavailable"));

        assertThatCode(() -> simulatorService.simulate("exp-1", List.of(
                new IdempotencySimulatedRequest("idem-1", "hash-a", 0)
        ), 0L)).doesNotThrowAnyException();

        verify(metricService).recordMetric("exp-1", "redisErrorCount", 1, "count", "Idempotency");
    }
}
