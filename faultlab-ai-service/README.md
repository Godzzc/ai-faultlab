# faultlab-ai-service

`faultlab-ai-service` is the Python FastAPI AI diagnosis service for AI FaultLab.

Current scope:

- Receive an Evidence Package from the Java backend.
- Generate a structured diagnosis report from `ruleResult`.
- Return a template-based report for later Java integration, Runbook RAG, and LLM wiring.
- Fall back to a safe report when evidence is insufficient or workflow execution fails.

This stage only returns template-based AI diagnosis reports. It does not call a real model and does not perform vector retrieval.

## Requirements

- Python 3.11+
- FastAPI
- Uvicorn
- Pydantic
- Pytest

## Install

```bash
pip install -r requirements.txt
```

## Start

```bash
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

## Health Check

```text
GET http://localhost:8000/ai/health
```

Response:

```json
{
  "service": "faultlab-ai-service",
  "status": "UP"
}
```

## Diagnosis API

```text
POST http://localhost:8000/ai/diagnosis/generate
```

The request body is the Evidence Package built by the Java backend. The response is structured JSON with `experimentId`, `faultType`, `faultName`, `confidence`, `summary`, `phenomenon`, `evidence`, `rootCauses`, `suggestions`, `runbookReferences`, and `fallback`.

## Not Included Yet

- Real LLM calls
- Runbook RAG
- LangGraph
- MCP

## Test

```bash
pytest
```
