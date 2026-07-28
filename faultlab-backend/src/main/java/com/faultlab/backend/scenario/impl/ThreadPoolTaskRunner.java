package com.faultlab.backend.scenario.impl;

import com.faultlab.backend.trace.annotation.TraceSpan;
import org.springframework.stereotype.Service;

@Service
public class ThreadPoolTaskRunner {

    @TraceSpan(operationName = "threadpool.execute.task", component = "ThreadPool")
    public void runBlockingTask(long taskSleepMs) {
        sleep(taskSleepMs);
    }

    private void sleep(long taskSleepMs) {
        if (taskSleepMs <= 0) {
            return;
        }
        try {
            Thread.sleep(taskSleepMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("thread pool saturation task interrupted", exception);
        }
    }
}
