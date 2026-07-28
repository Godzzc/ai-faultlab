package com.faultlab.backend.scenario.impl;

import com.faultlab.backend.metric.service.MetricService;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
