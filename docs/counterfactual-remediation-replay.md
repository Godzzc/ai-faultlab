# Counterfactual Remediation Replay

## Why

LLM / Agent output can propose remediation, but natural-language suggestions do not prove a fix is effective.

v0.15.0 Phase 2 maps a structured `RemediationPlan` into the controlled FaultLab experiment environment and re-executes the same fault scenario with only the approved remediation patch applied.

## Flow

```text
Original Experiment
        ↓
Diagnosis Agent
        ↓
RemediationPlan
        ↓
Java Replay Policy
        ↓
Clone Original Parameters
        ↓
Apply Allowlisted Patch
        ↓
Replay Experiment
```

## Counterfactual Invariant

Replay preserves the original experiment conditions except for allowlisted remediation parameters.

For example, a `RETRY_STORM` original experiment may contain:

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

With this patch:

```json
{
  "enableRetryLimit": true,
  "enableJitter": true
}
```

The replay keeps `requestCount`, `concurrency`, `failureRatio`, `maxRetries`, and `retryBackoffMs` unchanged. Only `enableRetryLimit` and `enableJitter` change.

The implementation clones `fault_experiment.params_json` and applies the allowlisted patch to the clone. It does not create a new parameter set from defaults.

## Double Validation

Remediation replay uses two validation layers:

```text
Python Remediation Policy
        +
Java Replay Policy
```

The Python policy constrains AI-generated `RemediationPlan` output. The Java policy validates again before execution because backend APIs cannot trust AI-generated input or client-submitted patches.

## API

```text
POST /api/experiments/{originalExperimentId}/remediation-replay
```

Request:

```json
{
  "planId": "plan_xxx",
  "parameterPatch": {
    "enableRetryLimit": true,
    "enableJitter": true
  }
}
```

`scenarioCode` is optional. If supplied, it must match the original experiment's `scenarioCode`.

Response:

```json
{
  "originalExperimentId": "exp_original",
  "replayExperimentId": "exp_replay",
  "scenarioCode": "RETRY_STORM",
  "originalParams": {
    "requestCount": 100,
    "concurrency": 20,
    "failureRatio": 0.7,
    "maxRetries": 3,
    "retryBackoffMs": 20,
    "enableRetryLimit": false,
    "enableJitter": false
  },
  "appliedPatch": {
    "enableRetryLimit": true,
    "enableJitter": true
  },
  "replayParams": {
    "requestCount": 100,
    "concurrency": 20,
    "failureRatio": 0.7,
    "maxRetries": 3,
    "retryBackoffMs": 20,
    "enableRetryLimit": true,
    "enableJitter": true
  },
  "status": "COMPLETED",
  "planId": "plan_xxx"
}
```

## Supported Scenarios

The first version supports:

- `CACHE_BREAKDOWN`
- `DB_CONNECTION_POOL_EXHAUSTION`
- `DOWNSTREAM_TIMEOUT`
- `RETRY_STORM`

Allowed patch parameters:

- `CACHE_BREAKDOWN`: `enableMutex`, `enableLogicalExpire`
- `DB_CONNECTION_POOL_EXHAUSTION`: `enableFastRelease`
- `DOWNSTREAM_TIMEOUT`: `enableFallback`
- `RETRY_STORM`: `enableRetryLimit`, `enableJitter`, `retryBackoffMs`

## Explicitly Rejected Parameters

The replay policy rejects `scenarioCode`, `experimentId`, unknown parameters, and fault-condition variables.

Examples of rejected condition variables:

- `CACHE_BREAKDOWN`: `requestCount`, `concurrency`, `hotKey`, `rebuildDelayMs`
- `DB_CONNECTION_POOL_EXHAUSTION`: `requestCount`, `concurrency`, `queryDelayMs`
- `DOWNSTREAM_TIMEOUT`: `requestCount`, `concurrency`, `downstreamDelayMs`, `timeoutRatio`
- `RETRY_STORM`: `requestCount`, `concurrency`, `failureRatio`

## Original Parameter Persistence

New experiments persist their startup parameter map in `fault_experiment.params_json`.

Replay experiments persist `source_experiment_id`, which points to the original experiment. Normal experiments keep `source_experiment_id = null`.

Existing databases need this schema update:

```sql
ALTER TABLE fault_experiment ADD COLUMN params_json TEXT DEFAULT NULL;
ALTER TABLE fault_experiment ADD COLUMN source_experiment_id VARCHAR(64) DEFAULT NULL;
CREATE INDEX idx_fault_experiment_source_experiment_id ON fault_experiment (source_experiment_id);
```

Old experiments whose `params_json` is missing cannot be replayed and return `ORIGINAL_EXPERIMENT_PARAMS_UNAVAILABLE`. The backend does not guess original parameters.

## Metrics And Trace

Replay uses the existing `FaultScenario` execution path. Metrics and Trace spans are written with the new `replayExperimentId`.

This keeps:

```text
Metrics(originalExperimentId)
Metrics(replayExperimentId)
Trace(originalExperimentId)
Trace(replayExperimentId)
```

naturally isolated.

## Current Boundary

v0.15.0 Phase 2 only performs controlled replay.

It does not:

- compare before/after metrics
- assign `VERIFIED`, `NOT_VERIFIED`, or `PARTIALLY_VERIFIED`
- score remediation effectiveness
- automatically modify production systems
- call Python Agent from Java replay
- make the Python Agent call Java replay

Remediation validation belongs to Phase 3.
