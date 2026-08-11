package com.faultlab.backend.scenario.model;

public final class ScenarioCode {

    public static final String MQ_BACKLOG = "MQ_BACKLOG";
    public static final String THREAD_POOL_SATURATION = "THREAD_POOL_SATURATION";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String CACHE_PENETRATION = "CACHE_PENETRATION";
    public static final String CACHE_BREAKDOWN = "CACHE_BREAKDOWN";
    public static final String CACHE_AVALANCHE = "CACHE_AVALANCHE";

    private ScenarioCode() {
    }
}
