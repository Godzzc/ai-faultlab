# RAG Metrics Snapshot

## 1. Purpose

This document records the current Runbook RAG retrieval evaluation snapshot for AI FaultLab. It is intended for project demos, resume metric extraction, and future version quality comparison.

The metrics below come only from the current repository files and local script execution on 2026-08-15. No retriever logic, evaluation cases, thresholds, Runbooks, or README content was changed for this snapshot.

## 2. Evaluation Dataset

Data source:

```text
faultlab-ai-service/evaluation/rag_eval_cases.json
```

Current file statistics:

- totalCases: 72
- faultType count: 12

| Fault Type | Case Count |
| ---------- | ---------- |
| CACHE_AVALANCHE | 5 |
| CACHE_BREAKDOWN | 5 |
| CACHE_PENETRATION | 5 |
| CIRCUIT_BREAKER_OPEN | 5 |
| DB_CONNECTION_POOL_EXHAUSTION | 5 |
| DB_LOCK_CONTENTION | 5 |
| DB_SLOW_QUERY | 5 |
| DOWNSTREAM_TIMEOUT | 5 |
| IDEMPOTENCY_CONFLICT | 9 |
| MQ_BACKLOG | 9 |
| RETRY_STORM | 5 |
| THREAD_POOL_SATURATION | 9 |

## 3. Retrievers

Current evaluation code supports these retrievers:

| Retriever | Current Local Evaluation Status |
| --------- | ------------------------------- |
| bm25 | Completed successfully. Uses local Markdown Runbooks and does not require Milvus or embedding service access. |
| hybrid | Not fully completed in this local snapshot. `evaluate_retrieval.py --retriever hybrid --top-k 3` timed out after 30 seconds. `evaluate_retrieval.py --retriever all --top-k 3` also timed out after 124 seconds. |
| milvus | Failed in this local snapshot because Milvus was unavailable on `localhost:19530`. |

Local dependency checks:

- `DASHSCOPE_API_KEY`: set
- Milvus TCP check on `localhost:19530`: `TcpTestSucceeded=False`
- Milvus evaluation error:

```text
<MilvusException: (code=2, message=Fail connecting to server on localhost:19530, illegal connection params or server unavailable)>
```

## 4. BM25 Regression Gate

Command executed:

```powershell
cd D:\JavaProjects\ai-faultlab\faultlab-ai-service
.\.venv\Scripts\python.exe scripts\check_rag_regression.py --retriever bm25
```

Result:

| Metric | Value | Threshold | Passed |
| ------ | ----- | --------- | ------ |
| caseCount | 72 | n/a | n/a |
| Hit@3 | 0.8194 | 0.6000 | true |
| Recall@3 | 0.5764 | 0.4500 | true |
| MRR | 0.6366 | 0.3500 | true |

Regression gate summary:

```text
retrieverName=bm25 topK=3 passed=True
failedMetrics=[]
message=bm25 retrieval regression check passed.
```

Threshold source:

```text
faultlab-ai-service/evaluation/rag_eval_thresholds.json
```

## 5. Snapshot Notes

The BM25 numbers are valid local retrieval metrics for the current checked-in evaluation dataset and Runbook files.

Hybrid and Milvus are listed as supported retrievers, but this snapshot does not claim successful hybrid or Milvus metrics because the local Milvus service was not reachable during evaluation.
