package com.faultlab.backend.scenario.idempotency;

public class IdempotencyCheckResult {

    public enum Status {
        ACCEPTED,
        DUPLICATE,
        CONFLICT,
        REDIS_ERROR
    }

    private final Status status;
    private final long checkDurationMs;

    public IdempotencyCheckResult(Status status, long checkDurationMs) {
        this.status = status;
        this.checkDurationMs = checkDurationMs;
    }

    public Status getStatus() {
        return status;
    }

    public long getCheckDurationMs() {
        return checkDurationMs;
    }
}
