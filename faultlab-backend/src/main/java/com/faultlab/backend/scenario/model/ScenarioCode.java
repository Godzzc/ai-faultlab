package com.faultlab.backend.scenario.model;

public final class ScenarioCode {

    public static final String MQ_BACKLOG = "MQ_BACKLOG";
    public static final String THREAD_POOL_SATURATION = "THREAD_POOL_SATURATION";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String CACHE_PENETRATION = "CACHE_PENETRATION";
    public static final String CACHE_BREAKDOWN = "CACHE_BREAKDOWN";
    public static final String CACHE_AVALANCHE = "CACHE_AVALANCHE";
    public static final String DB_SLOW_QUERY = "DB_SLOW_QUERY";
    public static final String DB_LOCK_CONTENTION = "DB_LOCK_CONTENTION";
    public static final String DB_CONNECTION_POOL_EXHAUSTION = "DB_CONNECTION_POOL_EXHAUSTION";
    public static final String DOWNSTREAM_TIMEOUT = "DOWNSTREAM_TIMEOUT";
    public static final String RETRY_STORM = "RETRY_STORM";
    public static final String CIRCUIT_BREAKER_OPEN = "CIRCUIT_BREAKER_OPEN";

    private ScenarioCode() {
    }
}
