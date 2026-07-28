package com.faultlab.backend.scenario.impl;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.trace.context.TraceContext;
import com.faultlab.backend.trace.context.TraceContextHolder;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ThreadPoolSaturationScenarioTests {

    private ThreadPoolExecutor executor;
    private final ThreadPoolTaskRunner threadPoolTaskRunner = mock(ThreadPoolTaskRunner.class);
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
    void shouldSubmitTasksWithoutBlockingStartApi() {
        executor = executor(1, 1);
        ThreadPoolSaturationScenario scenario = scenario();

        long startedAt = System.currentTimeMillis();
        scenario.execute("exp-1", Map.of("taskCount", 1, "taskSleepMs", 60_000));
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertThat(elapsedMs).isLessThan(500L);
        verify(metricService).recordMetric("exp-1", "acceptedTaskCount", 1, "count", "ThreadPool");
    }

    @Test
    void shouldRecordRejectedTaskCountWhenThreadPoolSaturated() {
        executor = executor(1, 1);
        doAnswer(invocation -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(threadPoolTaskRunner).runBlockingTask(250L);
        ThreadPoolSaturationScenario scenario = scenario();

        scenario.execute("exp-1", Map.of("taskCount", 5, "taskSleepMs", 250));

        verify(metricService).recordMetric("exp-1", "submittedTaskCount", 5, "count", "ThreadPool");
        verify(metricService).recordMetric("exp-1", "acceptedTaskCount", 2, "count", "ThreadPool");
        verify(metricService).recordMetric("exp-1", "rejectedTaskCount", 3, "count", "ThreadPool");
    }

    @Test
    void shouldRecordThreadPoolMetrics() {
        executor = executor(1, 2);
        ThreadPoolSaturationScenario scenario = scenario();

        scenario.execute("exp-1", Map.of("taskCount", 2, "taskSleepMs", 1000));

        verify(metricService).recordMetric("exp-1", "taskCount", 2, "count", "ThreadPool");
        verify(metricService).recordMetric("exp-1", "taskSleepMs", 1000L, "ms", "ThreadPool");
        verify(metricService).recordMetric("exp-1", "submittedTaskCount", 2, "count", "ThreadPool");
        verify(metricService).recordMetric(eq("exp-1"), eq("activeThreadCount"), any(Number.class), eq("count"), eq("ThreadPool"));
        verify(metricService).recordMetric(eq("exp-1"), eq("queueSize"), any(Number.class), eq("count"), eq("ThreadPool"));
        verify(metricService).recordMetric("exp-1", "avgTaskDurationMs", 1000L, "ms", "ThreadPool");
    }

    @Test
    void shouldPropagateTraceContextToThreadPoolTask() throws InterruptedException {
        executor = executor(1, 1);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<TraceContext> contextInWorker = new AtomicReference<>();
        doAnswer(invocation -> {
            contextInWorker.set(TraceContextHolder.get());
            latch.countDown();
            return null;
        }).when(threadPoolTaskRunner).runBlockingTask(0L);
        TraceContextHolder.set(new TraceContext("trace-1", "parent-span", "exp-1"));
        ThreadPoolSaturationScenario scenario = scenario();

        scenario.execute("exp-1", Map.of("taskCount", 1, "taskSleepMs", 0));

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(contextInWorker.get()).isNotNull();
        assertThat(contextInWorker.get().getTraceId()).isEqualTo("trace-1");
        assertThat(contextInWorker.get().getSpanId()).isEqualTo("parent-span");
        assertThat(contextInWorker.get().getExperimentId()).isEqualTo("exp-1");
    }

    @Test
    void shouldClearTraceContextAfterThreadPoolTask() throws InterruptedException {
        executor = executor(1, 1);
        CountDownLatch taskFinished = new CountDownLatch(1);
        CountDownLatch contextChecked = new CountDownLatch(1);
        AtomicReference<TraceContext> contextAfterTask = new AtomicReference<>();
        doAnswer(invocation -> {
            taskFinished.countDown();
            return null;
        }).when(threadPoolTaskRunner).runBlockingTask(0L);
        TraceContextHolder.set(new TraceContext("trace-1", "parent-span", "exp-1"));
        ThreadPoolSaturationScenario scenario = scenario();

        scenario.execute("exp-1", Map.of("taskCount", 1, "taskSleepMs", 0));
        assertThat(taskFinished.await(1, TimeUnit.SECONDS)).isTrue();
        executor.execute(() -> {
            contextAfterTask.set(TraceContextHolder.get());
            contextChecked.countDown();
        });

        assertThat(contextChecked.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(contextAfterTask.get()).isNull();
    }

    private ThreadPoolSaturationScenario scenario() {
        return new ThreadPoolSaturationScenario(executor, threadPoolTaskRunner, metricService);
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
