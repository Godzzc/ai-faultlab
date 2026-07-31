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
- Supports Hybrid Retrieval Basic: Milvus vector retrieval + BM25-like keyword retrieval + RRF fusion + lightweight rerank.
- Keeps keyword-only retrieval available when Milvus or embedding is unavailable.
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
- `MilvusRunbookRetriever`: vector retriever backed by Milvus.
- `Bm25RunbookRetriever`: local Markdown BM25-like keyword retriever.
- `HybridRunbookRetriever`: default retriever that combines vector and keyword results.
- `KeywordRunbookRetriever`: local Markdown fallback retriever.
- `reciprocal_rank_fusion`: RRF result fusion.
- `LightweightRunbookReranker`: rule-based reranker.
- `RetrievalService`: workflow-facing entry point.
- `RunbookChunk`: shared retrieval result model.

`RetrievalService` uses `HybridRunbookRetriever` by default. The hybrid retriever calls Milvus vector retrieval and BM25-like keyword retrieval, fuses the two ranked result lists with Reciprocal Rank Fusion, and then applies a lightweight rule-based rerank before returning the final topK chunks. If the hybrid retriever itself fails, `RetrievalService` still falls back to `KeywordRunbookRetriever`.

The Milvus retriever:

- Embeds the query with `text-embedding-v4`.
- Uses embedding dimension `1024`.
- Searches collection `faultlab_runbook_chunks`.
- Applies faultType filtering.
- Returns `RunbookChunk` objects to the hybrid retriever.

The BM25-like keyword retriever:

- Reads local Markdown files only.
- Parses `docId`, `title`, `faultType`, and `keywords`.
- Splits content by second-level headings (`##`) into sections.
- Applies strong filtering by `ruleResult.faultType` or `experiment.scenarioCode`.
- Extracts keywords from rule reason, rule evidence, metrics, and trace summary.
- Scores title, section, content, and runbook keywords with BM25-like keyword scoring.
- Returns the top matching chunks.

The current BM25 implementation is intentionally lightweight and dependency-free. It uses TF, IDF, document length normalization, title/section/keyword boosts, and a strong `faultType` boost, but it is not a full search-engine BM25 implementation.

The current rerank implementation is rule-based. It does not call an LLM, embedding model, or dedicated rerank model. It boosts chunks that match the fault type, operational sections such as troubleshooting and fixes, evidence metrics, metric keywords, and chunks found by both vector and keyword retrieval.

This version does not include FAISS, Elasticsearch, LangChain, LangGraph, MCP, Tool Calling, a dedicated rerank model, or a retrieval evaluation dataset.

Future upgrades can add:

- standard BM25
- BGE reranker or Alibaba Cloud Bailian rerank
- retrieval evaluation
- recall@k / MRR
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
- `hybrid_rrf_k`
- `hybrid_vector_top_k`
- `hybrid_bm25_top_k`
- `retrieval_top_k`
- `rerank_enabled`

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
- No retrieval evaluation dataset.
- No dedicated rerank model.

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

Runbook retrieval fallback is separate from diagnosis fallback. If Milvus retrieval or embedding fails, hybrid retrieval continues with BM25-like keyword results. If the hybrid retriever itself fails, diagnosis continues with `KeywordRunbookRetriever`.

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
13. Verify logs show `MilvusRunbookRetriever` and `Bm25RunbookRetriever` retrieving Runbook chunks.
14. Verify logs show RRF fusion and `LightweightRunbookReranker` execution.
15. Verify `runbookReferences` contains only valid retrieved references.
16. Stop Milvus and call diagnosis again to verify keyword-only retrieval still provides Runbook context.

## Test

```bash
python -m pytest
```
