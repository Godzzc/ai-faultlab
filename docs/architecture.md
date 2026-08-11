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
