# Remediation Workbench

## Purpose

The Remediation Workbench integrates the existing experiment, diagnosis, counterfactual replay, and deterministic validation APIs into the Vue frontend. It performs real API operations; it is not a static dashboard and it never applies a production fix.

## Start the services

Start MySQL, Redis, and RabbitMQ first, then run:

```bash
cd faultlab-backend
mvn spring-boot:run
```

```bash
cd faultlab-ai-service
python -m pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

```bash
cd faultlab-frontend
npm install
npm run dev
```

Open `http://localhost:5173`. Vite proxies `/api` to the Java backend and `/ai` to the Python service.

## Workflow

1. Select a scenario, configure its parameters, and click **开始演练**.
2. Run rule diagnosis, then click **生成 AI 诊断报告**.
3. Review the structured `RemediationPlan`. Natural-language suggestions are diagnosis context but are not executable.
4. Click **执行修复验证**, review the original experiment, proposed patch, and unchanged conditions, then confirm.
5. The frontend calls `POST /api/experiments/{originalExperimentId}/remediation-replay`. The Java Replay Policy revalidates every patch parameter and creates a new experiment.
6. The frontend loads the replay experiment and calls `GET /api/experiments/{replayExperimentId}/remediation-validation`.
7. Review the server-owned status, metric comparisons, applied patch, and supporting Trace comparison.

## Supported scenarios

- `CACHE_BREAKDOWN`
- `DB_CONNECTION_POOL_EXHAUSTION`
- `DOWNSTREAM_TIMEOUT`
- `RETRY_STORM`

Other scenarios return an `UNSUPPORTED` plan. `INVALID` and `UNSUPPORTED` plans cannot start Replay.

## Refresh recovery

After Replay, the URL contains `experimentId` and `replayExperimentId`. On refresh, these are lookup keys only. The frontend reloads the original experiment, persisted diagnosis, replay experiment, and Validation Report from the backend. It does not use `localStorage`, and loading never triggers Replay.

The replay's persisted `sourceExperimentId` must match the original experiment id before Validation is displayed.

## Common failures

- **No RemediationPlan**: generate a new AI diagnosis after both services are running. Old persisted reports predate the public plan field.
- **Original params unavailable**: old experiments without `params_json` cannot be replayed safely.
- **Replay rejected**: the Java Policy rejected a scenario mismatch, unknown key, invalid value, or unsupported scenario.
- **Validation INCONCLUSIVE**: required Metrics are missing or insufficient. This differs from `NOT_VERIFIED`, where comparable evidence exists but the expected effects did not match.
- **Replay/original mismatch**: the replay's `sourceExperimentId` does not match the original id in the URL.

## Validation boundary

The backend Validation Report is final. The frontend does not recompute status or derive `appliedPatch`. Metrics are primary evidence; Trace comparison is supporting evidence only. Validation is deterministic inside FaultLab and is not production A/B testing, statistical proof, or automated self-healing.
