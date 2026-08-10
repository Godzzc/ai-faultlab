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
- Supports RAG Retrieval Evaluation with Hit@K, Recall@K, and MRR.
- Supports Markdown RAG Evaluation Report generation for human review and comparison.
- Supports a RAG Evaluation Regression Gate for BM25-only CI checks.
- Supports RAG Retrieval Debug for inspecting query, vector, BM25, fusion, rerank, and final results.
- Supports Runbook Management Basic and synchronous Index Task records.
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
- Extracts terms from the shared retrieval query text, including fault type, rule name, rule reason, evidence, suggestions, metrics, and trace summary.
- Scores title, section, content, and runbook keywords with BM25-like keyword scoring.
- Returns the top matching chunks.

The current BM25 implementation is intentionally lightweight and dependency-free. It uses TF, IDF, document length normalization, title/section/keyword boosts, and a strong `faultType` boost, but it is not a full search-engine BM25 implementation.

For v0.8 retrieval tuning, Runbook front matter keywords and section content include more metric field names and English aliases from miss cases. `query_builder.py` also adds stable query fields and lightweight domain hints derived from metrics and evidence, while keeping deduplication and length control. This improves evaluation and debug consistency without introducing standard BM25 or a real rerank model.

The current rerank implementation is rule-based. It does not call an LLM, embedding model, or dedicated rerank model. It boosts chunks that match the fault type, operational sections such as troubleshooting and fixes, evidence metrics, metric keywords, and chunks found by both vector and keyword retrieval.

This version does not include FAISS, Elasticsearch, LangChain, LangGraph, MCP, Tool Calling, or a dedicated rerank model.

Future upgrades can add:

- standard BM25
- BGE reranker or Alibaba Cloud Bailian rerank
- more retrieval evaluation cases beyond the current 27-case baseline
- nDCG
- faultType grouped metrics
- retrieval result visualization
- stricter CI regression thresholds
- Runbook management UI

## RAG Retrieval Evaluation

RAG Retrieval Evaluation measures only the Runbook retrieval stage. It does not call the LLM, diagnosis workflow, Java backend, or frontend.

The evaluation dataset is stored at:

```text
evaluation/rag_eval_cases.json
```

The dataset currently contains 27 cases, expanded from the original 9-case baseline. Each fault type has at least 8 cases and covers more detailed MQ backlog, thread pool saturation, and idempotency conflict scenarios. Expected references remain strict `docId + section` pairs.

Each case defines a scenario query and expected `docId + section` references. The current metrics are:

- `Hit@K`: whether any expected `docId + section` appears in topK.
- `Recall@K`: matched expected references divided by total expected references.
- `MRR`: reciprocal rank of the first matched expected reference.

Evaluate retrieval through the AI Service endpoint:

```text
POST http://localhost:8000/ai/runbooks/evaluate
```

Request body is optional:

```json
{
  "retriever": "hybrid",
  "topK": 3
}
```

Supported `retriever` values are `bm25`, `hybrid`, `milvus`, and `all`. BM25 uses local Markdown only. Hybrid uses Milvus plus BM25 and will still return BM25 results if Milvus is unavailable. Pure Milvus evaluation returns a structured error when Milvus or embeddings are unavailable.

Run from the command line:

```bash
python scripts/evaluate_retrieval.py --retriever hybrid --top-k 3
python scripts/evaluate_retrieval.py --retriever all --top-k 3
```

The output includes summary metrics and each case's retrieved `docId + section`.

Generate a Markdown report:

```bash
python scripts/evaluate_retrieval.py --retriever all --top-k 3 --report
python scripts/evaluate_retrieval.py --retriever all --top-k 3 --report --output evaluation/reports/rag_eval_report.md
```

Generated Markdown reports under `evaluation/reports/*.md` are ignored by Git by default. The directory is kept with `evaluation/reports/.gitkeep`.

The evaluation API can also include a Markdown report while keeping `application/json` responses:

```text
POST http://localhost:8000/ai/runbooks/evaluate
body: {"retriever": "all", "topK": 3, "report": true}
```

When `report=true`, the response includes `markdownReport`. The report contains:

- Overall Metrics
- Retriever Comparison
- Metrics By Fault Type
- Case Details
- Miss Cases
- Optimization Suggestions

The optimization suggestions are rule-based and do not call an LLM. After the case expansion, metrics may decrease because the evaluation baseline is stricter; treat that as stronger coverage, not an automatic system regression. Use miss cases to guide later Runbook keyword, query construction, BM25-like scoring, and rerank weight tuning. Current reporting limits: no nDCG and no visualization UI.

## RAG Evaluation Regression Gate

The regression gate evaluates a retriever, compares Hit@K, Recall@K, and MRR against a checked-in threshold file, and exits with a CI-friendly status code.

Thresholds:

```text
evaluation/rag_eval_thresholds.json
```

The current thresholds are the v0.5 baseline. They are intentionally modest and do not represent production targets. They are not changed by the 27-case dataset expansion; if metrics drop, review miss cases first instead of lowering thresholds.

Run BM25 locally:

```powershell
.\.venv\Scripts\python.exe scripts\check_rag_regression.py --retriever bm25
```

Optional arguments:

```powershell
.\.venv\Scripts\python.exe scripts\check_rag_regression.py --retriever bm25 --top-k 3
.\.venv\Scripts\python.exe scripts\check_rag_regression.py --retriever bm25 --thresholds evaluation/rag_eval_thresholds.json
.\.venv\Scripts\python.exe scripts\check_rag_regression.py --retriever hybrid
```

`bm25` uses local Markdown runbooks only and does not require Milvus or `DASHSCOPE_API_KEY`. `hybrid` and `milvus` remain available for local or integration environments, but they depend on Milvus and embedding availability. If those dependencies are unavailable, the regression script returns a clear failure instead of passing silently.

GitHub Actions runs `python -m pytest` and the BM25-only gate:

```bash
python scripts/check_rag_regression.py --retriever bm25
```

When the gate fails, check Runbook keyword coverage, section title changes, query construction fields, BM25-like scoring changes, and accidental topK/RRF/rerank parameter changes.

## RAG Retrieval Debug

RAG Retrieval Debug runs only the retrieval chain and returns intermediate results for one Evidence Package or evaluation case. It does not call the LLM, PromptBuilder, Java backend, or frontend, and it does not write Milvus or index state.

API:

```text
POST /ai/runbooks/retrieve/debug
```

The request body is compatible with the diagnosis Evidence Package and adds:

- `topK`: final top K results, default `3`.
- `includeContent`: include chunk content in debug output, default `true`.

Response fields:

- `queryText`
- `faultType`
- `vectorResults`
- `bm25Results`
- `fusionResults`
- `rerankResults`
- `finalResults`
- `fallbackReason`
- `warnings`

CLI:

```powershell
.\.venv\Scripts\python.exe scripts\debug_retrieval.py --case-id mq_backlog_core_metrics --top-k 3
.\.venv\Scripts\python.exe scripts\debug_retrieval.py --case-id mq_backlog_core_metrics --top-k 3 --no-content
.\.venv\Scripts\python.exe scripts\debug_retrieval.py --case-id mq_backlog_core_metrics --output evaluation/reports/debug_mq_backlog.json
```

Use this to analyze miss cases, debug query construction, compare Milvus and BM25-like recall, and inspect RRF/rerank ordering changes. BM25 debug works without Milvus. Vector debug depends on Milvus and embedding availability; if unavailable, debug output keeps BM25 results and records the vector failure in `warnings`.

## Runbook Management Basic

Runbooks are still local Markdown files under `runbooks/`, but the AI service exposes basic management APIs:

```text
GET /ai/runbooks
GET /ai/runbooks/{docId}
POST /ai/runbooks
PUT /ai/runbooks/{docId}
DELETE /ai/runbooks/{docId}
```

`docId` must contain only lowercase letters, digits, and hyphens, such as `mq-backlog`. The service only reads and writes files inside the local `runbooks/` directory. Delete removes the Markdown file only; stale Milvus chunks are cleaned by the next index run.

Index task APIs:

```text
POST /ai/runbooks/index-tasks
GET /ai/runbooks/index-tasks
GET /ai/runbooks/index-tasks/{taskId}
```

`POST /ai/runbooks/index-tasks` runs indexing synchronously in this first version and records `taskId`, status, timestamps, duration, forceRebuild, counts, document lists, and errorMessage. Records are stored in:

```text
data/runbook_index_tasks.json
```

The existing `POST /ai/runbooks/index` is still supported and keeps the old response fields while adding `taskId`. `docIds` on the task request is accepted but reserved for future selective indexing.

Current limits: no MySQL Runbook tables, no async queue, no RabbitMQ indexing task, no permissions, no audit log, and no version approval workflow.

## RAG v0.5 Documentation

当前 AI Service 的 RAG 阶段定位为 `v0.5 RAG Demo`。核心链路是：

```text
Runbook Markdown
  -> section chunking
  -> embedding
  -> Milvus index
  -> Evidence Package
  -> Hybrid Retrieval
  -> RRF fusion
  -> lightweight rerank
  -> Runbook Context
  -> PromptBuilder
  -> LLM diagnosis
  -> runbookReferences validation
  -> Retrieval Evaluation Report
```

详细文档：

- [RAG v0.5 Demo Guide](../docs/rag-v0.5-demo-guide.md)
- [RAG Architecture](../docs/rag-architecture.md)
- [RAG Evaluation Guide](../docs/rag-evaluation-guide.md)
- [RAG Interview Guide](../docs/rag-interview-guide.md)

边界说明：

- BM25-like keyword retrieval 不是标准搜索引擎级 BM25。
- lightweight rerank 是规则型排序，不是真实 rerank 模型。
- 当前没有 Runbook 管理后台、可视化评测 UI 或 hybrid required regression gate。
- 当前索引状态使用本地 JSON，不适合多实例生产共享状态。

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
17. Run BM25 retrieval evaluation:

```bash
python scripts/evaluate_retrieval.py --retriever bm25 --top-k 3
```

18. Or call the evaluation API:

```text
POST http://localhost:8000/ai/runbooks/evaluate
body: {"retriever": "all", "topK": 3}
```

## Test

```bash
python -m pytest
```
