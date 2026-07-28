package com.faultlab.backend.scenario.idempotency;

public class IdempotencySimulatedRequest {

    private final String idempotencyKey;
    private final String requestHash;
    private final int requestIndex;

    public IdempotencySimulatedRequest(String idempotencyKey, String requestHash, int requestIndex) {
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.requestIndex = requestIndex;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public int getRequestIndex() {
        return requestIndex;
    }
}
