# Remediation Validation

## Why

Agent-generated remediation suggestions cannot be trusted only because they sound reasonable.

AI FaultLab validates candidate remediation inside a controlled fault experiment. The Java Backend compares the original experiment with the counterfactual replay experiment using persisted Metrics and Trace evidence.

No LLM call is used during validation.

## Full Loop

```text
Fault Experiment
    ↓
Metrics + Trace
    ↓
Diagnosis Agent
    ↓
RemediationPlan
    ↓
Counterfactual Replay
    ↓
Before / After Comparison
    ↓
Remediation Validation
```

## Validation Model

Metrics are the primary evidence.

Trace is supporting evidence. The first version reports span counts, error span counts, total duration, and operation-level counts, but Trace does not directly decide `VERIFIED`, `PARTIALLY_VERIFIED`, or `NOT_VERIFIED`.

The comparison is deterministic and reproducible: the same original metrics, replay metrics, params, and trace data produce the same report.

## API

```text
GET /api/experiments/{replayExperimentId}/remediation-validation
```

The request path uses the replay experiment id. The backend resolves the original experiment from `fault_experiment.source_experiment_id`.

The response is wrapped in the existing `ApiResponse` envelope and includes:

- `originalExperimentId`
- `replayExperimentId`
- `scenarioCode`
- `status`
- `appliedPatch`
- expected effect counts
- `metricComparisons`
- `traceComparison`
- deterministic `summary`
- `validatedAt`

## Status

`VERIFIED`

All required expected metric effects matched.

`PARTIALLY_VERIFIED`

At least one required expected metric effect matched and at least one required effect failed.

`NOT_VERIFIED`

Comparable metrics exist, but no required expected effect matched.

`INCONCLUSIVE`

The backend cannot reliably judge the remediation. Examples:

- no expected effects can be resolved
- required original or replay metric is missing
- metrics are insufficient for the expected effect set

Counterfactual invariant violations are returned as explicit errors instead of `NOT_VERIFIED`, because the experiments are not comparable.

## Counterfactual Invariant

Validation re-checks the invariant from Counterfactual Remediation Replay:

Only remediation variables may change between original params and replay params.

If fault conditions change, validation is not trustworthy. For example, a replay of `RETRY_STORM` cannot change `requestCount`, `concurrency`, `failureRatio`, or `maxRetries` and still claim the remediation was verified.

The backend derives `appliedPatch` by comparing `originalParams` and `replayParams`. It does not trust the client to resubmit the patch.

## Server-side Validation Policy

The Java Backend owns the validation policy. Clients cannot submit arbitrary expected effects such as:

```json
{
  "metricName": "downstream.retry.count",
  "direction": "INCREASE"
}
```

This prevents a client or agent from changing the success criteria after replay. The backend resolves expected effects from:

- `scenarioCode`
- original params
- replay params
- the derived applied patch

## Metric Comparison

Each expected metric effect produces a `MetricComparison`:

- `metricName`
- `beforeValue`
- `afterValue`
- `absoluteChange`
- `changePercent`
- `expectedDirection`
- `matchedExpectation`
- `status`
- `description`

Direction behavior:

- `DECREASE`: `afterValue < beforeValue`
- `INCREASE`: `afterValue > beforeValue`
- `STABLE`: absolute difference is within `0.000001`

When `beforeValue = 0`, `changePercent` is `null` and the comparison note is `baseline is zero`. Direction matching still works, so `0 -> 10` matches `INCREASE`.

v0.15.0 validation verifies expected direction in the controlled deterministic simulation. It does not perform production statistical significance testing.

## Supported Scenarios

### CACHE_BREAKDOWN

Allowed remediation variables:

- `enableMutex`
- `enableLogicalExpire`

Real metric names used by validation include:

- `cache.concurrent.rebuild.count`
- `cache.rebuild.count`
- `cache.db.query.count`
- `cache.hot.key.miss.count`
- `cache.miss.rate`

### DB_CONNECTION_POOL_EXHAUSTION

Allowed remediation variable:

- `enableFastRelease`

Real metric names used by validation:

- `db.connection.acquire.timeout.count`
- `db.connection.acquire.avg.ms`
- `db.connection.hold.avg.ms`
- `api.error.count`

### DOWNSTREAM_TIMEOUT

Allowed remediation variable:

- `enableFallback`

Real metric names used by validation:

- `api.error.count`
- `downstream.fallback.count`
- `downstream.timeout.count`

Fallback is expected to reduce API errors and increase fallback executions. It is not expected to reduce `downstream.timeout.count`, because the simulated downstream dependency is still slow.

### RETRY_STORM

Allowed remediation variables:

- `enableRetryLimit`
- `enableJitter`
- `retryBackoffMs`

Real metric names used by validation include:

- `downstream.retry.count`
- `downstream.retry.amplification.factor`
- `downstream.total.call.count`
- `downstream.retry.exhausted.count`
- `api.avg.latency.ms`

`downstream.retry.exhausted.count` is expected to stay stable when retry limiting is enabled, because the current Java simulation derives it from initial failures rather than retry policy.

## Error Handling

Validation uses the existing backend `ErrorCode` and `BusinessException` model.

Explicit validation errors include:

- `REMEDIATION_VALIDATION_REPLAY_NOT_FOUND`
- `REMEDIATION_VALIDATION_ORIGINAL_NOT_FOUND`
- `REMEDIATION_VALIDATION_SOURCE_MISSING`
- `REMEDIATION_VALIDATION_PARAMS_UNAVAILABLE`
- `REMEDIATION_VALIDATION_UNSUPPORTED_SCENARIO`
- `COUNTERFACTUAL_INVARIANT_VIOLATION`

Missing individual expected metrics are represented as inconclusive metric comparisons. The overall report becomes `INCONCLUSIVE`.

## Persistence

Validation reports are computed on demand.

No `remediation_validation` table is added in v0.15.0 Phase 3 because all required source data already exists:

- original experiment
- replay experiment
- metrics
- trace spans
- persisted params

## Limitations

Current validation is deterministic experiment validation.

It is not:

- production statistical A/B testing
- a chaos engineering benchmark
- automatic production self-healing
- proof of causality in real production systems
- LLM-as-a-judge

It validates that, inside FaultLab's controlled and reproducible experiment model, the candidate remediation produced the expected direction of metric change.
