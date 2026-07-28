package com.faultlab.backend.scenario.idempotency;

public class IdempotencySimulationResult {

    private final int requestCount;
    private int acceptedCount;
    private int rejectedDuplicateCount;
    private int hashMismatchCount;
    private int redisSetNxSuccessCount;
    private int redisSetNxFailCount;
    private int redisErrorCount;
    private long totalCheckDurationMs;

    public IdempotencySimulationResult(int requestCount) {
        this.requestCount = requestCount;
    }

    public void add(IdempotencyCheckResult checkResult) {
        totalCheckDurationMs += checkResult.getCheckDurationMs();
        switch (checkResult.getStatus()) {
            case ACCEPTED -> {
                acceptedCount++;
                redisSetNxSuccessCount++;
            }
            case DUPLICATE -> {
                rejectedDuplicateCount++;
                redisSetNxFailCount++;
            }
            case CONFLICT -> {
                hashMismatchCount++;
                redisSetNxFailCount++;
            }
            case REDIS_ERROR -> redisErrorCount++;
            default -> throw new IllegalStateException("unsupported idempotency status: " + checkResult.getStatus());
        }
    }

    public int getRequestCount() {
        return requestCount;
    }

    public int getAcceptedCount() {
        return acceptedCount;
    }

    public int getRejectedDuplicateCount() {
        return rejectedDuplicateCount;
    }

    public int getHashMismatchCount() {
        return hashMismatchCount;
    }

    public int getRedisSetNxSuccessCount() {
        return redisSetNxSuccessCount;
    }

    public int getRedisSetNxFailCount() {
        return redisSetNxFailCount;
    }

    public int getRedisErrorCount() {
        return redisErrorCount;
    }

    public long getAvgCheckDurationMs() {
        if (requestCount == 0) {
            return 0L;
        }
        return totalCheckDurationMs / requestCount;
    }
}
