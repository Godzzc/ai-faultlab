package com.faultlab.backend.scenario.idempotency;

import com.faultlab.backend.trace.annotation.TraceSpan;
import java.time.Duration;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyCheckService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate stringRedisTemplate;

    public IdempotencyCheckService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @TraceSpan(operationName = "idempotency.check", component = "Redis")
    public IdempotencyCheckResult checkRequest(
            String experimentId,
            IdempotencySimulatedRequest request,
            long processingDelayMs
    ) {
        long startedAt = System.currentTimeMillis();
        try {
            String businessKey = businessKey(experimentId, request.getIdempotencyKey());
            String hashKey = hashKey(experimentId, request.getIdempotencyKey());
            Boolean acquired = stringRedisTemplate.opsForValue()
                    .setIfAbsent(businessKey, "PROCESSING", IDEMPOTENCY_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                stringRedisTemplate.opsForValue().set(hashKey, request.getRequestHash(), IDEMPOTENCY_TTL);
                sleep(processingDelayMs);
                return result(IdempotencyCheckResult.Status.ACCEPTED, startedAt);
            }

            String existingHash = stringRedisTemplate.opsForValue().get(hashKey);
            if (Objects.equals(existingHash, request.getRequestHash())) {
                return result(IdempotencyCheckResult.Status.DUPLICATE, startedAt);
            }
            return result(IdempotencyCheckResult.Status.CONFLICT, startedAt);
        } catch (RuntimeException exception) {
            return result(IdempotencyCheckResult.Status.REDIS_ERROR, startedAt);
        }
    }

    private IdempotencyCheckResult result(IdempotencyCheckResult.Status status, long startedAt) {
        return new IdempotencyCheckResult(status, System.currentTimeMillis() - startedAt);
    }

    private String businessKey(String experimentId, String idempotencyKey) {
        return "idempotency:" + experimentId + ":" + idempotencyKey;
    }

    private String hashKey(String experimentId, String idempotencyKey) {
        return businessKey(experimentId, idempotencyKey) + ":hash";
    }

    private void sleep(long processingDelayMs) {
        if (processingDelayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(processingDelayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
