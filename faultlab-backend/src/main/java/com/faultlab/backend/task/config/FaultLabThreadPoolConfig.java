package com.faultlab.backend.task.config;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FaultLabThreadPoolConfig {

    public static final int CORE_POOL_SIZE = 2;
    public static final int MAX_POOL_SIZE = 2;
    public static final int QUEUE_CAPACITY = 10;

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor faultLabScenarioExecutor() {
        return new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAX_POOL_SIZE,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                new FaultLabThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static class FaultLabThreadFactory implements ThreadFactory {

        private final AtomicInteger index = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("faultlab-worker-" + index.getAndIncrement());
            return thread;
        }
    }
}
