# Architecture

AI FaultLab is organized around a single experiment start flow:

```text
POST /api/experiments/start
  -> ExperimentService
  -> FaultScenario implementation selected by scenarioCode
  -> FaultMetric records
  -> Trace spans
  -> RuleDiagnosisService
  -> EvidencePackageBuilder
  -> Python AI diagnosis service
```

## Scenario Registry

`ExperimentService` receives all Spring `FaultScenario` beans and builds an in-memory map keyed by `FaultScenario.scenarioCode()`. New scenarios only need to implement `FaultScenario`, return a code from `ScenarioCode`, and be registered as Spring components.

Supported scenario codes:

- `MQ_BACKLOG`
- `THREAD_POOL_SATURATION`
- `IDEMPOTENCY_CONFLICT`
- `CACHE_PENETRATION`
- `CACHE_BREAKDOWN`
- `CACHE_AVALANCHE`
- `DB_SLOW_QUERY`
- `DB_LOCK_CONTENTION`
- `DB_CONNECTION_POOL_EXHAUSTION`

## v0.9.0 Cache Scenarios

The cache scenarios live in the backend `scenario.cache` package and simulate cache behavior with deterministic local maps/sets. They do not add dependencies, do not modify the frontend, do not modify the Python AI service, and do not flush Redis.

`CACHE_PENETRATION` simulates nonexistent keys missing cache and DB. It can enable null-cache writes and a Set-backed filter that represents a Bloom filter.

`CACHE_BREAKDOWN` simulates one expired hot key. Without mutex, requests repeatedly query DB and rebuild cache. With mutex or logical expire, rebuild pressure is reduced.

`CACHE_AVALANCHE` simulates many keys expiring together or Redis being unavailable inside the scenario. TTL jitter and fallback reduce the blast radius.

Each cache scenario records a root `experiment.start` span from the shared experiment flow plus scenario child spans such as `cache.lookup`, `db.query`, `cache.lookup.hot-key`, `cache.rebuild`, `cache.batch.lookup`, `db.batch.query`, `fallback.local-cache`, and `redis.unavailable`.

## Diagnosis Chain

Cache rule diagnosis is handled by `CacheFailureRuleDiagnoser`. It reads cache metrics and returns the existing `RuleDiagnosisResult` shape with:

- `faultType`
- `faultName`
- `confidence`
- `matched`
- `reason`
- `evidence`
- `suggestions`

`EvidencePackageBuilder` is scenario-code agnostic: it loads the experiment, metrics, trace tree, and rule result. Therefore the new cache fault types are included in the same EvidencePackage sent to the AI service.

## v0.10.0 Database Bottleneck Scenarios

The database scenarios live in the backend `scenario.db` package and use deterministic in-process simulation. They do not create large MySQL tables, hold real long transactions, exhaust HikariCP, or run benchmark traffic.

`DB_SLOW_QUERY` simulates full table scan behavior by recording high scanned rows, slow query count, average query latency, and API latency. `queryMode=INDEXED` or `enableIndexOptimization=true` reduces scanned rows and slow query count.

`DB_LOCK_CONTENTION` simulates hotspot row lock waits with a local lock model. Long transaction mode records lock wait, timeout, active transaction, and update success metrics. Short transaction mode lowers wait and timeout pressure.

`DB_CONNECTION_POOL_EXHAUSTION` simulates a small database connection pool with a local semaphore model. Slow query hold time and high concurrency increase acquire timeout and API error metrics. Fast release reduces timeout pressure.

Each database scenario records the shared root `experiment.start` span plus scenario child spans such as `db.query`, `db.scan.rows`, `db.explain.check`, `db.transaction.begin`, `db.lock.acquire`, `db.lock.wait`, `db.update.row`, `db.connection.acquire`, `db.connection.wait`, `db.query.execute`, and `db.connection.release`.

Database rule diagnosis is handled by `DbFailureRuleDiagnoser`. `EvidencePackageBuilder` remains scenario-code agnostic, so `DB_SLOW_QUERY`, `DB_LOCK_CONTENTION`, and `DB_CONNECTION_POOL_EXHAUSTION` enter the existing AI diagnosis request chain without API changes.
