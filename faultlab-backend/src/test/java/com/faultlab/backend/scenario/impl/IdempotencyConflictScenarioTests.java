package com.faultlab.backend.scenario.impl;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.scenario.idempotency.IdempotencySimulatedRequest;
import com.faultlab.backend.scenario.idempotency.IdempotencySimulatorService;
import com.faultlab.backend.trace.context.TraceContext;
import com.faultlab.backend.trace.context.TraceContextHolder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IdempotencyConflictScenarioTests {

    private ThreadPoolExecutor executor;
    private final IdempotencySimulatorService idempotencySimulatorService = mock(IdempotencySimulatorService.class);
    private final MetricService metricService = mock(MetricService.class);

    @AfterEach
    void tearDown() throws InterruptedException {
        if (executor != null) {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
        TraceContextHolder.clear();
    }

    @Test
    void shouldNotBlockStartApi() {
        executor = executor(1, 10);
        doAnswer(invocation -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(idempotencySimulatorService).simulate(eq("exp-1"), any(), eq(60_000L));
        IdempotencyConflictScenario scenario = scenario();

        long startedAt = System.currentTimeMillis();
        scenario.execute("exp-1", Map.of("requestCount", 1, "processingDelayMs", 60_000));
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertThat(elapsedMs).isLessThan(500L);
    }

    @Test
    void shouldSubmitIdempotencySimulationRequests() throws InterruptedException {
        executor = executor(1, 10);
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(idempotencySimulatorService).simulate(eq("exp-1"), any(), eq(1000L));
        IdempotencyConflictScenario scenario = scenario();

        scenario.execute("exp-1", Map.of(
                "requestCount", 4,
                "duplicateCount", 2,
                "conflictCount", 1,
                "idempotencyKey", "idem-1"
        ));

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        ArgumentCaptor<List<IdempotencySimulatedRequest>> captor = ArgumentCaptor.forClass(List.class);
        verify(idempotencySimulatorService).simulate(eq("exp-1"), captor.capture(), eq(1000L));
        List<IdempotencySimulatedRequest> requests = captor.getValue();
        assertThat(requests).hasSize(4);
        assertThat(requests).extracting(IdempotencySimulatedRequest::getIdempotencyKey)
                .containsOnly("idem-1");
        assertThat(requests.get(0).getRequestHash()).isEqualTo(requests.get(1).getRequestHash());
        assertThat(requests.get(3).getRequestHash()).isNotEqualTo(requests.get(0).getRequestHash());
    }

    @Test
    void shouldRecordRedisErrorMetricWhenBatchSubmissionRejected() {
        executor = new ThreadPoolExecutor(
                1,
                1,
                60L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.shutdown();
        IdempotencyConflictScenario scenario = scenario();

        scenario.execute("exp-1", Map.of("requestCount", 3, "duplicateCount", 1, "conflictCount", 1));

        verify(metricService).recordMetric("exp-1", "requestCount", 3, "count", "Idempotency");
        verify(metricService).recordMetric("exp-1", "redisErrorCount", 3, "count", "Idempotency");
    }

    @Test
    void shouldPropagateTraceContextToIdempotencySimulationTask() throws InterruptedException {
        executor = executor(1, 10);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TraceContext> contextInWorker = new AtomicReference<>();
        doAnswer(invocation -> {
            contextInWorker.set(TraceContextHolder.get());
            latch.countDown();
            return null;
        }).when(idempotencySimulatorService).simulate(eq("exp-1"), any(), eq(1000L));
        TraceContextHolder.set(new TraceContext("trace-1", "parent-span", "exp-1"));
        IdempotencyConflictScenario scenario = scenario();

        scenario.execute("exp-1", Map.of("requestCount", 1));

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(contextInWorker.get()).isNotNull();
        assertThat(contextInWorker.get().getTraceId()).isEqualTo("trace-1");
        assertThat(contextInWorker.get().getSpanId()).isEqualTo("parent-span");
        assertThat(contextInWorker.get().getExperimentId()).isEqualTo("exp-1");
    }

    private IdempotencyConflictScenario scenario() {
        return new IdempotencyConflictScenario(executor, idempotencySimulatorService, metricService);
    }

    private ThreadPoolExecutor executor(int poolSize, int queueCapacity) {
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
