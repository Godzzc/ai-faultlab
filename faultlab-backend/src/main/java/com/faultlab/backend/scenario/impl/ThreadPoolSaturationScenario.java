package com.faultlab.backend.scenario.impl;

import com.faultlab.backend.metric.service.MetricService;
import com.faultlab.backend.scenario.FaultScenario;
import com.faultlab.backend.scenario.model.ScenarioCode;
import com.faultlab.backend.trace.annotation.TraceSpan;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ThreadPoolSaturationScenario implements FaultScenario {

    public static final String SCENARIO_NAME = "线程池饱和";
    public static final int DEFAULT_TASK_COUNT = 20;
    public static final long DEFAULT_TASK_SLEEP_MS = 3000L;

    private final ThreadPoolExecutor faultLabScenarioExecutor;
    private final ThreadPoolTaskRunner threadPoolTaskRunner;
    private final MetricService metricService;

    public ThreadPoolSaturationScenario(
            @Qualifier("faultLabScenarioExecutor") ThreadPoolExecutor faultLabScenarioExecutor,
            ThreadPoolTaskRunner threadPoolTaskRunner,
            MetricService metricService
    ) {
        this.faultLabScenarioExecutor = faultLabScenarioExecutor;
        this.threadPoolTaskRunner = threadPoolTaskRunner;
        this.metricService = metricService;
    }

    @Override
    public String scenarioCode() {
        return ScenarioCode.THREAD_POOL_SATURATION;
    }

    @Override
    public String scenarioName() {
        return SCENARIO_NAME;
    }

    @Override
    @TraceSpan(operationName = "threadpool.submit.tasks", component = "ThreadPool")
    public void execute(String experimentId, Map<String, Object> params) {
        int taskCount = readInt(params, "taskCount", DEFAULT_TASK_COUNT);
        long taskSleepMs = readLong(params, "taskSleepMs", DEFAULT_TASK_SLEEP_MS);

        int acceptedTaskCount = 0;
        int rejectedTaskCount = 0;
        for (int index = 0; index < taskCount; index++) {
            try {
                // TODO: Propagate TraceContext with a TaskDecorator or wrapped Runnable in a later iteration.
                faultLabScenarioExecutor.execute(() -> threadPoolTaskRunner.runBlockingTask(taskSleepMs));
                acceptedTaskCount++;
            } catch (RejectedExecutionException exception) {
                rejectedTaskCount++;
            }
        }

        recordMetrics(experimentId, taskCount, taskSleepMs, acceptedTaskCount, rejectedTaskCount);
    }

    private void recordMetrics(
            String experimentId,
            int taskCount,
            long taskSleepMs,
            int acceptedTaskCount,
            int rejectedTaskCount
    ) {
        metricService.recordMetric(experimentId, "taskCount", taskCount, "count", "ThreadPool");
        metricService.recordMetric(experimentId, "taskSleepMs", taskSleepMs, "ms", "ThreadPool");
        metricService.recordMetric(experimentId, "submittedTaskCount", taskCount, "count", "ThreadPool");
        metricService.recordMetric(experimentId, "acceptedTaskCount", acceptedTaskCount, "count", "ThreadPool");
        metricService.recordMetric(experimentId, "rejectedTaskCount", rejectedTaskCount, "count", "ThreadPool");
        metricService.recordMetric(experimentId, "activeThreadCount", faultLabScenarioExecutor.getActiveCount(), "count", "ThreadPool");
        metricService.recordMetric(experimentId, "queueSize", faultLabScenarioExecutor.getQueue().size(), "count", "ThreadPool");
        metricService.recordMetric(experimentId, "avgTaskDurationMs", taskSleepMs, "ms", "ThreadPool");
    }

    private int readInt(Map<String, Object> params, String key, int defaultValue) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return defaultValue;
    }

    private long readLong(Map<String, Object> params, String key, long defaultValue) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return defaultValue;
    }
}
