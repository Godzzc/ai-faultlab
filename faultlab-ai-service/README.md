# faultlab-ai-service

`faultlab-ai-service` is the Python FastAPI AI diagnosis service for AI FaultLab.

Current scope:

- Receive an Evidence Package from the Java backend.
- Generate a structured diagnosis report with Alibaba Cloud Bailian through the OpenAI-compatible API.
- Validate LLM JSON output into the fixed `DiagnosisResponse` schema.
- Fall back to a rule-based template report when the LLM is disabled, unconfigured, unavailable, or returns invalid JSON.

## Requirements

- Python 3.11+
- FastAPI
- Uvicorn
- Pydantic
- Pytest
- OpenAI Python SDK

## Configuration

Set environment variables before starting the service:

```bash
DASHSCOPE_API_KEY=你的百炼APIKey
DASHSCOPE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
DASHSCOPE_MODEL=qwen-plus
LLM_ENABLED=true
LLM_TIMEOUT_SECONDS=20
LLM_MAX_RETRIES=1
```

If `DASHSCOPE_API_KEY` is empty, or `LLM_ENABLED=false`, the service automatically returns a fallback report and does not call the LLM.

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

- Runbook RAG
- LangGraph
- MCP
- Vector retrieval
- Tool Calling

## Test

```bash
python -m pytest
```
