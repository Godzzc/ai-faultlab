# Remediation Planning

## Why

Current AI diagnosis reports can provide natural-language `suggestions`, but free-form text is not a stable contract for later automated verification.

v0.15.0 Phase 1 adds a structured proposal layer:

```text
Natural Language Suggestion
    ↓
Structured RemediationPlan
```

The plan describes what parameters could be changed in a future replay. It does not execute any fix.

## Model

`RemediationPlan` contains `planId`, `experimentId`, `scenarioCode`, `actions`, `expectedEffects`, `evidence`, `confidence`, `status`, and `warnings`.

`RemediationAction` contains `actionType`, `description`, `parameterPatch`, and `rationale`.

`ExpectedMetricEffect` contains `metricName`, `direction`, and `description`. Supported directions are `INCREASE`, `DECREASE`, and `STABLE`.

## Supported Scenarios

The first version supports:

- `CACHE_BREAKDOWN`
- `DB_CONNECTION_POOL_EXHAUSTION`
- `DOWNSTREAM_TIMEOUT`
- `RETRY_STORM`

Other scenarios return `UNSUPPORTED` with no action rather than inventing parameters.

## Parameter Policy

The Agent cannot freely modify experiment parameters. Every `parameterPatch` is validated against a scenario-specific whitelist and type/value policy.

Allowed v0.15.0 parameters:

- `CACHE_BREAKDOWN`: `enableMutex`, `enableLogicalExpire`
- `DB_CONNECTION_POOL_EXHAUSTION`: `enableFastRelease`
- `DOWNSTREAM_TIMEOUT`: `enableFallback`
- `RETRY_STORM`: `enableRetryLimit`, `enableJitter`, `retryBackoffMs`

Unknown keys are rejected. `scenarioCode` and `experimentId` are never allowed inside `parameterPatch`.

## Planning Rules

Planning is deterministic and rule-based. It does not add another LLM call.

Current mappings:

- `CACHE_BREAKDOWN` with rebuild or hot-key miss evidence proposes `ENABLE_CACHE_MUTEX`.
- `DB_CONNECTION_POOL_EXHAUSTION` with acquire timeout, acquire latency, hold time, or API error evidence proposes `ENABLE_FAST_RELEASE`.
- `DOWNSTREAM_TIMEOUT` with timeout or API error evidence proposes `ENABLE_FALLBACK`.
- `RETRY_STORM` with retry or amplification evidence proposes `ENABLE_RETRY_LIMIT` and `ENABLE_RETRY_JITTER`.

## Current Boundary

`RemediationPlan` is a proposal only.

v0.15.0 does not:

- execute the plan
- modify Java Backend state
- modify databases, Redis, RabbitMQ, code, or production systems
- compare before/after metrics
- mark a plan as verified

The Python Diagnosis Agent still only creates a proposal. v0.15.0 Phase 2 adds a separate Java Backend Counterfactual Remediation Replay API that can consume the plan's `parameterPatch` in the controlled FaultLab experiment environment. Remediation validation and before/after comparison remain future work.
