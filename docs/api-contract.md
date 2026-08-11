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
