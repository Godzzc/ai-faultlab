# AI FaultLab RAG Evaluation Guide

## RAG Evaluation Regression Gate

The regression gate compares retrieval metrics against a checked-in v0.5 baseline:

```text
faultlab-ai-service/evaluation/rag_eval_thresholds.json
```

Local command:

```powershell
cd faultlab-ai-service
.\.venv\Scripts\python.exe scripts\check_rag_regression.py --retriever bm25
```

The output includes `hitAtK`, `recallAtK`, `mrr`, each threshold, `passed`, and `failedMetrics`. The process exits with `0` when all metrics pass and `1` when any metric fails.

The first GitHub Actions gate runs only BM25 retrieval, so it does not require Milvus, embeddings, or `DASHSCOPE_API_KEY`. Hybrid and Milvus evaluation can still be run locally, but they depend on Milvus and embedding availability and are not required by CI yet.

These thresholds are v0.5 baseline values, not production metrics. They can be raised later after the evaluation dataset becomes larger and more stable. When the gate fails, check Runbook keywords, section titles, query construction fields, BM25-like scoring, and accidental topK/RRF/rerank parameter changes.

## RAG Retrieval Debug

Use retrieval debug when an evaluation case misses and logs are not enough to understand the ranking path.

API:

```text
POST /ai/runbooks/retrieve/debug
```

CLI:

```powershell
cd faultlab-ai-service
.\.venv\Scripts\python.exe scripts\debug_retrieval.py --case-id mq_backlog_core_metrics --top-k 3
```

Debug output includes `queryText`, `vectorResults`, `bm25Results`, `fusionResults`, `rerankResults`, `finalResults`, and `warnings`. It is useful for analyzing miss cases, query construction, Runbook keyword coverage, Milvus and BM25-like recall differences, RRF fusion, and lightweight rerank ordering changes.

Current limits: no LLM call, no frontend UI, no visualization chart, no standard BM25, and no real rerank model. Milvus debug depends on Milvus and embedding availability. BM25 debug can run without Milvus.

## v0.8 Retrieval Tuning Notes

The v0.8 retrieval tuning pass uses the expanded 27-case evaluation dataset and miss cases to improve Runbook keyword coverage and query construction. Runbook updates add section-level metric names and English aliases for MQ backlog, thread pool saturation, and idempotency conflict scenarios.

Query construction now keeps `faultType`, `faultName`, `reason`, `evidence`, `suggestions`, metric name/value/component, and trace summary signals in the retrieval query text with basic deduplication and length control. It also adds lightweight domain hints derived from metric and evidence fields, such as `slow consumer`, `rejection policy`, `unique index`, and `Redis error`.

This remains BM25-like keyword retrieval, not standard BM25. The reranker remains lightweight and rule-based, not a real rerank model.

## v0.9 Cache Evaluation Cases

The v0.9 cache expansion moved the retrieval evaluation dataset from 27 to 42 cases. The current v0.10 dataset is 57 cases after database bottleneck additions. The 15 cache cases cover:

- `CACHE_PENETRATION`: 5 cases for invalid key, null cache, bloom filter, DB pressure, and risk control.
- `CACHE_BREAKDOWN`: 5 cases for hot key expiry, rebuild storm, mutex lock, logical expire, and singleflight.
- `CACHE_AVALANCHE`: 5 cases for same TTL, Redis unavailable, TTL jitter, fallback, and DB spike.

Expected references remain strict `docId + section` pairs. The new cache Runbook sections are `现象`, `核心指标`, `常见原因`, `排查步骤`, `修复建议`, and `风险提示`; every expected section must exist in the Markdown Runbook.

The BM25 regression thresholds are unchanged. If metrics fall after adding cases, first inspect miss cases and tune Runbook wording or keywords. Do not lower `evaluation/rag_eval_thresholds.json` just to pass the gate.

Milvus and Hybrid evaluation still depend on local Milvus, embedding service availability, and whether newly added Runbooks have been indexed. BM25 evaluation does not require Milvus or external API calls.

## v0.10 Database Evaluation Cases

The retrieval evaluation dataset now expands from 42 to 57 cases. The added 15 cases cover:

- `DB_SLOW_QUERY`: 5 cases for full scan, index miss, high scanned rows, execution plan checks, and optimization suggestions.
- `DB_LOCK_CONTENTION`: 5 cases for long transaction, hot row, lock wait timeout, transaction scope, and optimistic lock.
- `DB_CONNECTION_POOL_EXHAUSTION`: 5 cases for active pool saturation, acquire timeout, slow query connection holding, connection leak risk, and pool sizing suggestions.

Expected references remain strict `docId + section` pairs. The new database Runbook sections are `现象`, `核心指标`, `常见原因`, `排查步骤`, `修复建议`, and `风险提示`; every expected section must exist in the Markdown Runbook.

The BM25 regression thresholds are unchanged. If metrics fall after adding cases, first inspect miss cases and tune Runbook wording or keywords. Do not lower `evaluation/rag_eval_thresholds.json` just to pass the gate.

BM25-like retrieval is still not standard BM25, and lightweight rerank is still rule-based rather than a real rerank model. Hybrid and Milvus evaluation still require local Milvus, embedding service availability, and a current Runbook vector index.

## v0.8 Scoring and Rerank Tuning

The v0.8 scoring pass keeps the retrieval stack dependency-free and tunes only the explainable rules. BM25-like scoring now applies explicit boosts for matching `faultType`, section titles, front matter keywords, content terms, metric names, and evidence keys, with a light length normalization so longer sections do not win only because they contain more words.

The lightweight reranker now uses rule-based section intent signals. Reason or root-cause style queries can lift `常见原因`, suggestion or remediation queries can lift `修复建议`, metric and evidence-heavy queries can lift `核心指标`, diagnostic checks can lift `排查步骤`, and risk terms can lift `风险提示`. This is still not a standard BM25 implementation and still not a real rerank model.

The tuning is validated with the expanded evaluation cases and the BM25 regression gate. A metric change should be interpreted together with miss case details, partial recall cases, and reciprocal rank changes, not as a standalone signal.

本文档说明 AI FaultLab 当前 RAG v0.5 的检索评测能力。评测只覆盖 retrieval，不调用 LLM，不修改诊断主流程。

## 1. 为什么需要 RAG Evaluation

RAG 链路不能只靠人工观察“看起来像召回了相关内容”。如果缺少评测，很难回答这些问题：

- 某类故障是否召回了正确 Runbook section。
- Hybrid retrieval 是否比单路召回更稳定。
- 修改 Runbook、query construction、RRF 参数或 rerank 权重后，效果是否退化。

因此当前引入一个小型、可扩展的 retrieval evaluation dataset，用指标对召回结果做基础量化。

## 2. 当前评测数据集

评测数据集位置：

```text
faultlab-ai-service/evaluation/rag_eval_cases.json
```

当前包含 57 个 case，已从早期 9 个 case 扩充为更稳定的评测基线，覆盖：

- `MQ_BACKLOG`
- `THREAD_POOL_SATURATION`
- `IDEMPOTENCY_CONFLICT`
- `CACHE_PENETRATION`
- `CACHE_BREAKDOWN`
- `CACHE_AVALANCHE`
- `DB_SLOW_QUERY`
- `DB_LOCK_CONTENTION`
- `DB_CONNECTION_POOL_EXHAUSTION`

早期三类故障各至少 8 个 case，cache 和 database 扩展场景各 5 个 case。每个 expected 仍然使用严格的 `docId + section`，并且 section 必须来自已有 Runbook。

每个 case 包含：

- `caseId`
- `scenarioCode`
- query 中的 `ruleResult` 和 `metrics`
- expected `docId + section`

命中判断使用严格的 `docId + section`。例如：

```json
{
  "docId": "mq-backlog",
  "section": "核心指标"
}
```

这意味着只召回同一个文档但 section 不对，不算完整命中。

## 3. 指标定义

### Hit@K

topK 结果中只要命中任意 expected `docId + section`，该 case 的 hit 就是 1，否则是 0。

### Recall@K

topK 命中的 expected refs 数量 / expected refs 总数。

如果一个 case 期望两个 section，只召回一个，则 Recall@K = 0.5。

### MRR

MRR 使用第一个命中的 expected ref 的倒数排名。

例子：

- 第一个结果命中：MRR = 1.0
- 第二个结果命中：MRR = 0.5
- 没有命中：MRR = 0

## 4. API 评测

接口：

```text
POST http://localhost:8000/ai/runbooks/evaluate
```

评测 BM25-like：

```json
{"retriever":"bm25","topK":3}
```

评测 Hybrid：

```json
{"retriever":"hybrid","topK":3}
```

评测全部并返回 Markdown 报告：

```json
{"retriever":"all","topK":3,"report":true}
```

`retriever=all` 会分别评测：

- `bm25`
- `hybrid`
- `milvus`

如果某个 retriever 失败，响应会包含该 retriever 的错误信息，不影响其他 retriever 的结果。

## 5. CLI 评测

进入 AI Service 目录：

```powershell
cd faultlab-ai-service
```

普通评测：

```powershell
.\.venv\Scripts\python.exe scripts\evaluate_retrieval.py --retriever bm25 --top-k 3
```

输出 Markdown 报告到控制台：

```powershell
.\.venv\Scripts\python.exe scripts\evaluate_retrieval.py --retriever all --top-k 3 --report
```

输出 Markdown 报告到文件：

```powershell
.\.venv\Scripts\python.exe scripts\evaluate_retrieval.py --retriever all --top-k 3 --report --output evaluation/reports/rag_eval_report.md
```

生成的 `evaluation/reports/*.md` 默认不提交，目录通过 `.gitkeep` 保留。

## 6. Markdown Report

Markdown report 面向人工阅读、复盘和博客整理。报告包含：

- Overview
- Overall Metrics
- Retriever Comparison
- Metrics By Fault Type
- Case Details
- Miss Cases
- Optimization Suggestions

其中 Optimization Suggestions 是规则型建议，不调用 LLM。

## 7. 如何解读评测结果

### 扩充数据集后指标下降是正常现象

评测集从 9 个 case 扩充到 57 个 case 后，BM25-like、Milvus 或 Hybrid 的 Hit@K、Recall@K、MRR 可能下降。这通常说明评测集覆盖了更细的故障表达和更难的 section 匹配，不应直接解释为系统退化。

后续优化应基于 miss case 逐步调整 Runbook keywords、query construction、BM25-like scoring 和 rerank 权重，而不是降低 regression threshold 或放宽 expected 匹配标准。

### Hybrid 不一定每个 case 都最好

Hybrid 的目标是提升整体稳定性，不保证每个 case 都优于 BM25-like 或 Milvus。评测时应看整体指标、按 faultType 分组指标，以及具体 miss case。

### BM25-like miss 是 baseline signal

如果 BM25-like 对某个 case miss，通常说明：

- Runbook keywords 不够。
- section 标题或正文没有覆盖指标名。
- query 中的字段名和 Runbook 表达没有对齐。

### Miss case 用于指导优化

常见优化方向：

- 补充 Runbook keywords。
- 调整 section 标题。
- 在 Runbook section 中增加专业指标名、英文别名和字段名。
- 优化 query construction，让 Evidence 中的关键字段更稳定地进入 query。
- 调整 RRF `k`、rerank 权重和 topK。

## 8. 当前限制

- case 数量已经扩充到 57 个，但仍不是生产级大规模评测集。
- 没有 nDCG。
- hybrid gate 尚未作为 CI 必过项。
- Milvus evaluation 依赖 Milvus 和 embedding 可用。
- 当前优化建议是规则型，不调用 LLM。
- 当前 BM25-like 不是标准搜索引擎级 BM25。
- 当前 lightweight rerank 不是真实 rerank 模型。

## 9. 后续计划

- 基于 miss case 继续扩充和维护 evaluation case。
- 按 faultType 统计趋势。
- 增加 nDCG。
- 增加检索结果可视化。
- 增加 CI 回归门禁。
- 引入真实 rerank 模型后，与 lightweight rerank 做对比。
