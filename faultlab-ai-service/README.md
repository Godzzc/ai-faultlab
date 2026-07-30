# faultlab-ai-service

`faultlab-ai-service` is the Python FastAPI AI diagnosis service for AI FaultLab.

The service receives an Evidence Package from the Java backend, builds a constrained diagnosis prompt, retrieves local Runbook context, calls the Alibaba Cloud Bailian OpenAI-compatible API, validates strict JSON output, and falls back to rule-based diagnosis when LLM diagnosis is unavailable.

## Current Capabilities

- Receives Evidence Package input.
- Builds evidence-constrained LLM prompts.
- Supports Runbook RAG Basic with local Markdown runbooks.
- Retrieves runbooks by faultType filtering plus simple keyword scoring.
- Injects retrieved Runbook Context into the diagnosis prompt.
- Validates `runbookReferences` so only retrieved `docId` and `section` pairs are retained.
- Calls Alibaba Cloud Bailian through the OpenAI-compatible API.
- Supports basic `ModelRouter` model selection.
- Parses and validates LLM JSON output.
- Fills missing fields and clamps `confidence` to `0..1`.
- Falls back automatically when LLM is disabled, API key is missing, LLM errors, response is empty, or JSON is invalid.

## Runbook RAG Basic

Runbook RAG Basic uses Markdown files from `runbooks/`.

Each runbook should include YAML-style front matter:

```markdown
---
docId: mq-backlog
title: MQ backlog runbook
faultType: MQ_BACKLOG
keywords: RabbitMQ, publishCount, consumeCount, backlogCount
---
```

The retriever:

- Reads local Markdown files only.
- Parses `docId`, `title`, `faultType`, and `keywords`.
- Splits content by second-level headings (`##`) into sections.
- Applies strong filtering by `ruleResult.faultType` or `experiment.scenarioCode`.
- Extracts keywords from rule reason, rule evidence, metrics, and trace summary.
- Scores title, section, content, and runbook keywords with simple keyword matching.
- Returns the top matching chunks.

This version does not depend on a vector database or agent framework. It does not include Milvus, FAISS, Elasticsearch, LangChain, LangGraph, MCP, or Tool Calling.

Future upgrades can add:

- chunk embedding
- vector retrieval
- BM25
- rerank
- Runbook management UI

## Configuration

Only `DASHSCOPE_API_KEY` is required as an environment variable:

```bash
DASHSCOPE_API_KEY=your-bailian-api-key
```

Other LLM settings use defaults in `app/config.py`, including:

- `dashscope_base_url`
- `llm_enabled`
- `llm_timeout_seconds`
- `llm_max_retries`
- `llm_default_model`
- `llm_fast_model`
- `llm_reasoning_model`
- `llm_long_context_model`

## ModelRouter

`ModelRouter` chooses a model based on diagnosis complexity:

- missing `ruleResult` or `matched=false`: fast model
- large trace tree: reasoning model
- rich rule evidence: reasoning model
- many metrics: long-context model
- default diagnosis: default model

The selected model is logged only. It does not change the external API response structure.

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

The request body is the Evidence Package assembled by the Java backend:

- `experiment`
- `metrics`
- `traceTree`
- `ruleResult`

The response is a fixed `DiagnosisResponse`:

```json
{
  "experimentId": "exp_xxx",
  "faultType": "MQ_BACKLOG",
  "faultName": "MQ backlog",
  "confidence": 0.85,
  "summary": "The evidence indicates an MQ backlog risk.",
  "phenomenon": [],
  "evidence": [],
  "rootCauses": [],
  "suggestions": [],
  "runbookReferences": [],
  "fallback": false
}
```

## Fallback

Fallback returns a displayable rule-based report when:

- `DASHSCOPE_API_KEY` is not configured
- `settings.llm_enabled=False`
- LLM call times out or raises
- LLM returns empty content
- LLM returns non-JSON content
- JSON structure is invalid

Fallback reports keep `ruleResult.evidence` and `ruleResult.suggestions` when available.

## Local Verification

1. Confirm `DASHSCOPE_API_KEY` is configured.
2. Start the service:

```bash
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

3. Call:

```text
POST http://localhost:8000/ai/diagnosis/generate
```

4. Use an `MQ_BACKLOG` Evidence Package.
5. Verify `fallback=false` when the LLM call succeeds.
6. Verify `runbookReferences` contains only valid retrieved references, or at least confirm logs show retrieved Runbook chunks.
7. Check Uvicorn logs for Runbook retrieval count plus `docId`, `section`, and `score`.

## Test

```bash
python -m pytest
```
