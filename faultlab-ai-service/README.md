# faultlab-ai-service

`faultlab-ai-service` is the Python FastAPI AI diagnosis service for AI FaultLab.

Current scope:

- Receive an Evidence Package from the Java backend.
- Generate a structured diagnosis report with Alibaba Cloud Bailian through the OpenAI-compatible API.
- Route diagnosis requests to a basic model tier based on evidence complexity.
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

Only `DASHSCOPE_API_KEY` needs to be configured as an environment variable:

```bash
DASHSCOPE_API_KEY=your-bailian-api-key
```

Other LLM settings use code defaults in `app/config.py`.

Current defaults:

- `dashscope_base_url`: `https://dashscope.aliyuncs.com/compatible-mode/v1`
- `llm_timeout_seconds`: `90`
- `llm_max_retries`: `0`
- fast model: `qwen-turbo`
- default model: `qwen-plus`
- reasoning model: `qwen-plus`
- long context model: `qwen-plus`

If `DASHSCOPE_API_KEY` is empty, or `settings.llm_enabled` is `False`, the service automatically returns a fallback report and does not call the LLM.

## Model Routing

The service includes a basic `ModelRouter`:

- Missing or unmatched `ruleResult`: fast model
- Large Trace tree: reasoning model
- Rich rule evidence: reasoning model
- Many metrics: long context model
- Normal diagnosis: default model

Model selection is logged with the selected model and route reason. To adjust model names or runtime defaults, edit `app/config.py`.

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
- Vector retrieval
- Tool Calling
- LangGraph
- MCP

## Test

```bash
python -m pytest
```
