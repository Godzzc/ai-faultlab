# API Contract

## Start Experiment

```text
POST /api/experiments/start
Content-Type: application/json
```

Request:

```json
{
  "scenarioCode": "CACHE_PENETRATION",
  "params": {}
}
```

Response uses the existing unified response wrapper. The data payload contains:

```json
{
  "experimentId": "exp_xxx",
  "traceId": "xxx",
  "status": "RUNNING"
}
```

## Cache Penetration

Scenario code: `CACHE_PENETRATION`

Params:

```json
{
  "requestCount": 100,
  "invalidKeyRatio": 0.8,
  "enableNullCache": false,
  "enableBloomFilter": false,
  "dbDelayMs": 20
}
```

Metrics:

- `cache.request.count`
- `cache.hit.count`
- `cache.miss.count`
- `cache.miss.rate`
- `cache.db.query.count`
- `cache.db.query.rate`
- `cache.invalid.key.count`
- `cache.null.cache.write.count`
- `cache.bloom.reject.count`
- `cache.avg.db.query.ms`

Rule diagnosis matches when miss rate, DB query count, invalid key count, or missing mitigation indicates penetration risk.

## Cache Breakdown

Scenario code: `CACHE_BREAKDOWN`

Params:

```json
{
  "requestCount": 100,
  "hotKey": "hot:item:1",
  "concurrency": 20,
  "rebuildDelayMs": 100,
  "enableMutex": false,
  "enableLogicalExpire": false
}
```

Metrics:

- `cache.hot.key.request.count`
- `cache.hot.key.miss.count`
- `cache.miss.rate`
- `cache.db.query.count`
- `cache.rebuild.count`
- `cache.lock.acquire.count`
- `cache.lock.fail.count`
- `cache.rebuild.duration.ms`
- `cache.concurrent.rebuild.count`

Rule diagnosis matches when hot-key requests concentrate and DB query, rebuild, or concurrent rebuild counts spike.

## Cache Avalanche

Scenario code: `CACHE_AVALANCHE`

Params:

```json
{
  "keyCount": 50,
  "requestCount": 200,
  "sameTtl": true,
  "enableTtlJitter": false,
  "simulateRedisDown": false,
  "enableFallback": false,
  "dbDelayMs": 20
}
```

Metrics:

- `cache.key.count`
- `cache.expired.key.count`
- `cache.unavailable.count`
- `cache.request.count`
- `cache.hit.count`
- `cache.miss.count`
- `cache.miss.rate`
- `cache.db.query.count`
- `cache.db.slow.query.count`
- `cache.fallback.count`
- `cache.request.error.count`

Rule diagnosis matches when many keys miss together, DB query count rises, Redis is simulated unavailable without enough fallback, or request errors increase.

## Diagnosis Endpoints

The existing diagnosis endpoints support the new cache fault types without a contract change:

```text
POST /api/diagnosis/{experimentId}/rule
POST /api/diagnosis/{experimentId}/ai/generate
GET  /api/diagnosis/{experimentId}
```

EvidencePackage includes the experiment `scenarioCode`, metrics, trace tree, and `RuleDiagnosisResult` for `CACHE_PENETRATION`, `CACHE_BREAKDOWN`, and `CACHE_AVALANCHE`.

## DB Slow Query

Scenario code: `DB_SLOW_QUERY`

Params:

```json
{
  "requestCount": 100,
  "queryMode": "FULL_SCAN",
  "tableSize": 100000,
  "scannedRows": 80000,
  "dbDelayMs": 80,
  "enableIndexOptimization": false
}
```

`queryMode` supports `INDEXED` and `FULL_SCAN`.

Metrics:

- `db.query.count`
- `db.slow.query.count`
- `db.slow.query.rate`
- `db.avg.query.ms`
- `db.max.query.ms`
- `db.full.scan.count`
- `db.index.hit.count`
- `db.scanned.rows`
- `db.table.size`
- `api.avg.latency.ms`

Rule diagnosis matches when slow query rate, average query latency, full scan count, or scanned rows indicate slow SQL risk.

## DB Lock Contention

Scenario code: `DB_LOCK_CONTENTION`

Params:

```json
{
  "requestCount": 50,
  "concurrency": 10,
  "targetRowId": "order:1",
  "lockHoldMs": 200,
  "lockWaitTimeoutMs": 100,
  "enableShortTransaction": false
}
```

Metrics:

- `db.lock.request.count`
- `db.lock.wait.count`
- `db.lock.wait.rate`
- `db.lock.wait.ms`
- `db.avg.lock.wait.ms`
- `db.max.lock.wait.ms`
- `db.long.transaction.count`
- `db.transaction.active.count`
- `db.update.success.count`
- `db.update.timeout.count`
- `api.timeout.count`

Rule diagnosis matches when lock waits, average wait time, update timeout count, or long transaction count indicate row lock contention.

## DB Connection Pool Exhaustion

Scenario code: `DB_CONNECTION_POOL_EXHAUSTION`

Params:

```json
{
  "requestCount": 100,
  "concurrency": 30,
  "maxPoolSize": 10,
  "queryDelayMs": 200,
  "connectionAcquireTimeoutMs": 50,
  "enableFastRelease": false
}
```

Metrics:

- `db.pool.max.size`
- `db.pool.active.count`
- `db.pool.idle.count`
- `db.connection.acquire.count`
- `db.connection.acquire.timeout.count`
- `db.connection.acquire.timeout.rate`
- `db.connection.acquire.avg.ms`
- `db.connection.hold.avg.ms`
- `db.query.count`
- `api.error.count`

Rule diagnosis matches when acquire timeout count, active pool saturation, acquire wait time, or API error count indicates pool exhaustion.

The existing diagnosis endpoints support the database fault types without a contract change. EvidencePackage includes the experiment `scenarioCode`, metrics, trace tree, and `RuleDiagnosisResult` for `DB_SLOW_QUERY`, `DB_LOCK_CONTENTION`, and `DB_CONNECTION_POOL_EXHAUSTION`.

## Downstream Timeout

Scenario code: `DOWNSTREAM_TIMEOUT`

Params:

```json
{
  "requestCount": 100,
  "concurrency": 20,
  "downstreamDelayMs": 300,
  "timeoutMs": 100,
  "timeoutRatio": 0.8,
  "enableFallback": false,
  "fallbackDelayMs": 10
}
```

Metrics:

- `downstream.request.count`
- `downstream.timeout.count`
- `downstream.timeout.rate`
- `downstream.avg.latency.ms`
- `downstream.max.latency.ms`
- `downstream.slow.call.count`
- `downstream.fallback.count`
- `api.avg.latency.ms`
- `api.error.count`
- `api.success.count`

Rule diagnosis matches when timeout rate, downstream latency, max latency, API errors, or missing fallback indicate downstream timeout risk.

## Retry Storm

Scenario code: `RETRY_STORM`

Params:

```json
{
  "requestCount": 100,
  "concurrency": 20,
  "failureRatio": 0.7,
  "maxRetries": 3,
  "retryBackoffMs": 20,
  "enableRetryLimit": false,
  "enableJitter": false
}
```

Metrics:

- `downstream.initial.request.count`
- `downstream.total.call.count`
- `downstream.retry.count`
- `downstream.retry.rate`
- `downstream.retry.exhausted.count`
- `downstream.failure.count`
- `downstream.success.count`
- `downstream.retry.amplification.factor`
- `api.error.count`
- `api.avg.latency.ms`

Rule diagnosis matches when retry count, total downstream calls, retry amplification, retry exhaustion, or API errors indicate retry storm risk.

## Circuit Breaker Open

Scenario code: `CIRCUIT_BREAKER_OPEN`

Params:

```json
{
  "requestCount": 100,
  "failureRatio": 0.8,
  "slowCallRatio": 0.5,
  "slidingWindowSize": 20,
  "failureRateThreshold": 0.5,
  "slowCallThresholdMs": 200,
  "openDurationMs": 500,
  "enableFallback": true
}
```

Metrics:

- `circuit.request.count`
- `circuit.failure.count`
- `circuit.failure.rate`
- `circuit.slow.call.count`
- `circuit.slow.call.rate`
- `circuit.open.count`
- `circuit.half.open.count`
- `circuit.rejected.count`
- `circuit.fallback.count`
- `downstream.call.skipped.count`
- `api.error.count`

Rule diagnosis matches when failure rate, slow-call rate, open count, rejected count, or skipped downstream calls indicate an opened circuit breaker.

The existing diagnosis endpoints support the downstream fault types without a contract change. EvidencePackage includes the experiment `scenarioCode`, metrics, trace tree, and `RuleDiagnosisResult` for `DOWNSTREAM_TIMEOUT`, `RETRY_STORM`, and `CIRCUIT_BREAKER_OPEN`.
