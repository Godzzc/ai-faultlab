# faultlab-ai-service

`faultlab-ai-service` is the Python FastAPI AI diagnosis service for AI FaultLab.

The service receives an Evidence Package from the Java backend, builds a constrained diagnosis prompt, retrieves Runbook context, calls the Alibaba Cloud Bailian OpenAI-compatible API, validates strict JSON output, and falls back to rule-based diagnosis when LLM diagnosis is unavailable.

## Current Capabilities

- Receives Evidence Package input.
- Builds evidence-constrained LLM prompts.
- Supports Runbook RAG Basic with local Markdown runbooks.
- Uses an abstract retrieval layer for Runbook retrieval.
- Supports Milvus Vector Retrieval for Runbook chunks.
- Uses Alibaba Cloud Bailian `text-embedding-v4` for Runbook embeddings.
- Falls back to `KeywordRunbookRetriever` when Milvus retrieval fails or returns no chunks.
- Injects retrieved Runbook Context into the diagnosis prompt.
- Validates `runbookReferences` so only retrieved `docId` and `section` pairs are retained.
- Calls Alibaba Cloud Bailian through the OpenAI-compatible API.
- Supports basic `ModelRouter` model selection.
- Parses and validates LLM JSON output.
- Fills missing fields and clamps `confidence` to `0..1`.
- Falls back automatically when LLM is disabled, API key is missing, LLM errors, response is empty, or JSON is invalid.

## Runbook RAG

Runbook RAG uses Markdown files from `runbooks/`. The files are split into section chunks, embedded with Alibaba Cloud Bailian `text-embedding-v4`, and stored in Milvus.

Each runbook should include YAML-style front matter:

```markdown
---
docId: mq-backlog
title: MQ backlog runbook
faultType: MQ_BACKLOG
keywords: RabbitMQ, publishCount, consumeCount, backlogCount
---
```

The retrieval layer is organized around:

- `BaseRunbookRetriever`: shared retriever interface.
- `MilvusRunbookRetriever`: current primary retriever.
- `KeywordRunbookRetriever`: local Markdown fallback retriever.
- `RetrievalService`: workflow-facing entry point.
- `RunbookChunk`: shared retrieval result model.

`RetrievalService` first tries `MilvusRunbookRetriever`. If Milvus is unavailable, the collection is missing, embedding generation fails, or Milvus returns no chunks, it falls back to `KeywordRunbookRetriever`.

The Milvus retriever:

- Embeds the query with `text-embedding-v4`.
- Uses embedding dimension `1024`.
- Searches collection `faultlab_runbook_chunks`.
- Applies faultType filtering.
- Returns `RunbookChunk` objects to the existing prompt builder.

The fallback keyword retriever:

- Reads local Markdown files only.
- Parses `docId`, `title`, `faultType`, and `keywords`.
- Splits content by second-level headings (`##`) into sections.
- Applies strong filtering by `ruleResult.faultType` or `experiment.scenarioCode`.
- Extracts keywords from rule reason, rule evidence, metrics, and trace summary.
- Scores title, section, content, and runbook keywords with simple keyword matching.
- Returns the top matching chunks.

This version is vector retrieval with keyword fallback. It is not Hybrid Retrieval and does not include Rerank, FAISS, Elasticsearch, LangChain, LangGraph, MCP, or Tool Calling.

Future upgrades can add:

- Hybrid Retrieval
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
- `embedding_model`
- `embedding_dimension`
- `milvus_host`
- `milvus_port`
- `milvus_collection_name`
- `retrieval_mode`

Only `DASHSCOPE_API_KEY` is read from the environment. Milvus and embedding settings currently use code defaults in `app/config.py`.

## Runbook Indexing

Start Milvus from Docker Compose, then index local runbooks:

```text
POST http://localhost:8000/ai/runbooks/index
```

Request body is optional:

```json
{
  "forceRebuild": false
}
```

Response:

```json
{
  "status": "success",
  "collectionName": "faultlab_runbook_chunks",
  "indexedCount": 12,
  "skippedCount": 0,
  "deletedCount": 0,
  "failedCount": 0,
  "indexedDocuments": ["mq-backlog", "thread-pool-saturation", "idempotency-conflict"],
  "skippedDocuments": [],
  "failedDocuments": [],
  "forceRebuild": false
}
```

RAG indexing governance Basic:

- Calculates a SHA-256 content hash for each Markdown Runbook.
- Skips unchanged documents on repeated indexing.
- Deletes old Milvus chunks and reindexes when a document changes.
- Cleans old Milvus chunks when a Runbook Markdown file is removed.
- Supports `forceRebuild=true` to rebuild all documents.
- Stores index state in `faultlab-ai-service/data/runbook_index_state.json`.

`runbook_index_state.json` is a runtime file and must not be committed. Indexing is explicit; the service does not index runbooks during startup.

Current indexing limitations:

- No MySQL index state table.
- No management UI.
- No scheduled scanner.
- No async indexing queue.
- No rollback mechanism.
- No Hybrid Retrieval or Rerank.

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

Runbook retrieval fallback is separate from diagnosis fallback. If Milvus retrieval fails, diagnosis continues with `KeywordRunbookRetriever`.

## Local Verification

1. Confirm `DASHSCOPE_API_KEY` is configured.
2. Start Docker Compose from `deploy/`:

```bash
docker compose up -d
docker compose ps
```

3. Start the service:

```bash
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

4. Build the Runbook index for the first time:

```text
POST http://localhost:8000/ai/runbooks/index
```

5. Verify `indexedCount > 0`.
6. Call the same endpoint again and verify `indexedCount = 0` and `skippedCount > 0`.
7. Force rebuild:

```text
POST http://localhost:8000/ai/runbooks/index
body: {"forceRebuild": true}
```

8. Modify one Markdown file under `runbooks/`, index again, and verify only the changed document is rebuilt.
9. Delete one Markdown file locally, index again, and verify old chunks are deleted and the state entry is removed.
10. Call diagnosis:

```text
POST http://localhost:8000/ai/diagnosis/generate
```

11. Use an `MQ_BACKLOG` Evidence Package.
12. Verify `fallback=false` when the LLM call succeeds.
13. Verify logs show `MilvusRunbookRetriever` retrieving Runbook chunks.
14. Verify `runbookReferences` contains only valid retrieved references.
15. Stop Milvus and call diagnosis again to verify fallback to `KeywordRunbookRetriever`.

## Test

```bash
python -m pytest
```
