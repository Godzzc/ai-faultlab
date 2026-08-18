# AI FaultLab RAG v0.5 Demo Guide

## v0.5 Debug Update

The RAG demo now includes a BM25 CI regression gate and a retrieval debug path:

```text
POST /ai/runbooks/retrieve/debug
```

```powershell
cd faultlab-ai-service
.\.venv\Scripts\python.exe scripts\debug_retrieval.py --case-id mq_backlog_core_metrics --top-k 3
```

Debug output includes `queryText`, `vectorResults`, `bm25Results`, `fusionResults`, `rerankResults`, `finalResults`, and `warnings`. Use it to analyze miss cases, debug query construction, compare Milvus and BM25 recall, and inspect RRF/rerank ordering changes.

Current limits still apply: no LLM call in debug mode, no frontend UI, no visualization chart, no real rerank model, and no required hybrid gate in CI. Milvus debug depends on Milvus and embeddings; BM25 debug works without Milvus.

## v0.5 Runbook Management Update

The AI service now exposes Runbook Management Basic and Index Task Basic while keeping the local Markdown design:

```text
GET /ai/runbooks
GET /ai/runbooks/{docId}
POST /ai/runbooks
PUT /ai/runbooks/{docId}
DELETE /ai/runbooks/{docId}
POST /ai/runbooks/index-tasks
GET /ai/runbooks/index-tasks
GET /ai/runbooks/index-tasks/{taskId}
```

`POST /ai/runbooks/index` remains compatible and now includes `taskId`. Index task records are stored in `data/runbook_index_tasks.json`; this runtime file should not be committed.

Current limits: still no MySQL Runbook tables, no async indexing queue, no permissions, no audit log, and no version approval workflow.

本文档用于演示 AI FaultLab 当前 RAG v0.5 阶段能力。它面向本地演示、面试复盘和技术博客整理，不把当前能力描述为生产级平台。

## 1. Demo 目标

本次演示重点不是展示一个通用聊天机器人，而是展示一条面向后端故障诊断的、证据驱动的 RAG 链路：

- 故障演练产生 Evidence，包括 Metrics、Trace、Rule Diagnosis。
- Runbook Markdown 离线切分并索引到 Milvus。
- 在线诊断时基于 Evidence 做 Hybrid Retrieval。
- 检索到的 Runbook Context 注入 Prompt。
- LLM 基于 Evidence Package 和 Runbook Context 生成结构化诊断报告。
- `runbookReferences` 可以追溯到本次召回的 `docId + section`。
- RAG Evaluation 用 Hit@K、Recall@K、MRR 量化检索效果，并生成 Markdown 报告。

推荐主演示路径使用 `MQ_BACKLOG`，因为它的指标、规则诊断和 Runbook section 都比较直观。

## 2. 当前版本能力边界

当前阶段可以定位为：

```text
v0.5 RAG Demo
```

已完成：

- Runbook Markdown 知识库。
- 基于二级标题的 section chunking。
- 阿里云百炼 `text-embedding-v4` embedding。
- Milvus vector retrieval。
- Okapi BM25 retrieval。
- RRF fusion。
- lightweight rule-based rerank。
- Runbook index management basic。
- content hash。
- `forceRebuild`。
- 文档变更后旧 chunk 清理。
- Keyword-only fallback。
- Prompt 注入 Runbook Context。
- `runbookReferences` 校验。
- Retrieval Evaluation。
- Hit@K、Recall@K、MRR。
- Markdown Evaluation Report。

未完成：

- 标准搜索引擎级 BM25。
- 真实 rerank 模型。
- Runbook 管理后台。
- 多实例共享索引状态。
- CI regression gate。
- 可视化评测 UI。

演示时需要明确：当前是可运行、可解释、可评测的 RAG demo，不是生产级知识治理平台。

## 3. 环境准备

### Windows PowerShell

启动基础设施：

```powershell
cd D:\JavaProjects\ai-faultlab\deploy
docker compose up -d
docker compose ps
```

确认应包含：

- `faultlab-mysql`
- `faultlab-redis`
- `faultlab-rabbitmq`
- `faultlab-milvus-etcd`
- `faultlab-milvus-minio`
- `faultlab-milvus-standalone`

配置百炼 API Key：

```powershell
setx DASHSCOPE_API_KEY "your-real-api-key"
```

重新打开 PowerShell 后确认：

```powershell
echo $env:DASHSCOPE_API_KEY
```

启动 Python AI Service：

```powershell
cd D:\JavaProjects\ai-faultlab\faultlab-ai-service
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

启动 Java Backend：

```powershell
cd D:\JavaProjects\ai-faultlab\faultlab-backend
mvn spring-boot:run
```

也可以用 IDEA 运行 `FaultLabBackendApplication`。

启动 Vue Frontend：

```powershell
cd D:\JavaProjects\ai-faultlab\faultlab-frontend
npm install
npm.cmd run dev
```

访问：

```text
http://localhost:5173
```

### WSL / Bash

启动基础设施：

```bash
cd /mnt/d/JavaProjects/ai-faultlab/deploy
docker compose up -d
docker compose ps
```

配置百炼 API Key：

```bash
export DASHSCOPE_API_KEY="your-real-api-key"
```

启动 Python AI Service：

```bash
cd /mnt/d/JavaProjects/ai-faultlab/faultlab-ai-service
python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

启动 Java Backend：

```bash
cd /mnt/d/JavaProjects/ai-faultlab/faultlab-backend
mvn spring-boot:run
```

启动 Vue Frontend：

```bash
cd /mnt/d/JavaProjects/ai-faultlab/faultlab-frontend
npm install
npm run dev
```

## 4. Runbook 索引演示

索引接口只属于 Python AI Service：

```text
POST http://localhost:8000/ai/runbooks/index
```

第一次索引：

```powershell
$body = '{}' 
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/ai/runbooks/index" -ContentType "application/json" -Body $body
```

期望现象：

- `indexedCount > 0`
- `failedCount = 0`
- `indexedDocuments` 包含 `mq-backlog`、`thread-pool-saturation`、`idempotency-conflict`

这证明 Markdown Runbook 被解析、切分、embedding，并写入 Milvus。

第二次索引：

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/ai/runbooks/index" -ContentType "application/json" -Body '{}'
```

期望现象：

- `indexedCount = 0`
- `skippedCount > 0`

这证明 content hash 和本地 index state 能识别未变化文档，避免重复 embedding 和重复写入。

强制重建：

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/ai/runbooks/index" -ContentType "application/json" -Body '{"forceRebuild":true}'
```

期望现象：

- `forceRebuild = true`
- 已有文档会重新 embedding 和 upsert。

这证明运维侧可以显式重建索引，适合 embedding 参数、Milvus collection 或文档策略调整后的重建。

修改一个 Runbook 后再次索引：

```powershell
notepad D:\JavaProjects\ai-faultlab\faultlab-ai-service\runbooks\mq-backlog.md
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/ai/runbooks/index" -ContentType "application/json" -Body '{}'
```

期望现象：

- 只重建变化文档。
- 未变化文档继续 skipped。
- 变化文档旧 chunk 会被删除后重新写入。

这证明索引治理不是全量粗暴重建，而是基于文档 hash 的增量治理。

删除一个 Runbook 后再次索引：

```powershell
# 演示时建议先备份再删除，演示后恢复。
Copy-Item .\runbooks\mq-backlog.md .\runbooks\mq-backlog.md.bak
Remove-Item .\runbooks\mq-backlog.md
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/ai/runbooks/index" -ContentType "application/json" -Body '{}'
Move-Item .\runbooks\mq-backlog.md.bak .\runbooks\mq-backlog.md
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/ai/runbooks/index" -ContentType "application/json" -Body '{"forceRebuild":true}'
```

期望现象：

- 删除文档对应旧 chunk 被清理。
- 本地 index state 中对应文档状态被移除。

这证明索引治理包含 stale chunk 清理，不只是追加写入。

## 5. AI 诊断演示

主演示路径使用 `MQ_BACKLOG`。

演示步骤：

1. 打开前端：

```text
http://localhost:5173
```

2. 选择 `MQ_BACKLOG`。
3. 使用默认参数或设置较明显的堆积参数，例如 `messageCount=10`、`consumerDelayMs=1000`。
4. 启动故障实验。
5. 执行规则诊断。
6. 生成 AI 诊断报告。
7. 查看 AI Report。
8. 查看 Runbook References。

一个合理的输出特征：

```json
{
  "fallback": false,
  "faultType": "MQ_BACKLOG",
  "runbookReferences": [
    {
      "docId": "mq-backlog",
      "section": "核心指标"
    },
    {
      "docId": "mq-backlog",
      "section": "常见原因"
    },
    {
      "docId": "mq-backlog",
      "section": "修复建议"
    }
  ],
  "evidence": [
    "publishCount=10",
    "consumeCount=1",
    "backlogCount=9",
    "avgConsumeMs=5000"
  ]
}
```

实际输出由 Rule Diagnosis、Trace、Metrics 和 LLM 共同决定，不要求逐字一致。演示时重点说明：

- `fallback=false` 说明 LLM 诊断成功。
- `faultType=MQ_BACKLOG` 与规则诊断一致。
- `runbookReferences` 是本次实际召回 Runbook Context 中允许引用的 `docId + section`。
- evidence 里应能看到 `publishCount`、`consumeCount`、`backlogCount`、`avgConsumeMs` 这类关键指标。

## 6. Hybrid Retrieval 演示

当前在线链路：

```text
Evidence Package
  -> query construction
  -> Milvus vector retrieval
  -> Okapi BM25 retrieval
  -> RRF fusion
  -> lightweight rerank
  -> Runbook Context
  -> LLM diagnosis
```

可以从 AI Service 日志确认以下关键词：

- `MilvusRunbookRetriever`
- `Bm25RunbookRetriever`
- `HybridRunbookRetriever`
- `RRF fusion`
- `LightweightRunbookReranker`
- `RetrievalService chunk docId=... section=... score=...`

演示讲法：

- Milvus 负责语义召回，适合“消费变慢导致堆积”这类语义表达。
- BM25 负责精确词召回，适合 `publishCount`、`consumeCount`、`backlogCount` 这类指标名。
- RRF 不直接混合不同检索器的分数，而是基于排名融合，避免向量分数和关键词分数不可比。
- lightweight rerank 是规则型，不是真实 rerank 模型；它会根据 faultType、section 类型、证据命中和双路召回等因素加权。

## 7. Fallback 演示

停止 Milvus：

```powershell
cd D:\JavaProjects\ai-faultlab\deploy
docker compose stop milvus-standalone
```

再次调用 MQ_BACKLOG 诊断：

- 诊断接口不应返回 500。
- Hybrid 中 vector retrieval 会失败或为空。
- BM25 retrieval 仍可返回本地 Markdown Runbook Context。
- 如果 LLM 可用，仍可基于 Evidence 和 BM25 Runbook Context 生成报告。

恢复 Milvus：

```powershell
docker compose start milvus-standalone
```

这个演示证明：RAG 增强失败时不应该拖垮主诊断流程，系统优先保证可降级。

## 8. RAG Evaluation 演示

评测接口：

```text
POST http://localhost:8000/ai/runbooks/evaluate
```

BM25：

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/ai/runbooks/evaluate" -ContentType "application/json" -Body '{"retriever":"bm25","topK":3}'
```

Hybrid：

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/ai/runbooks/evaluate" -ContentType "application/json" -Body '{"retriever":"hybrid","topK":3}'
```

All + Markdown report：

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/ai/runbooks/evaluate" -ContentType "application/json" -Body '{"retriever":"all","topK":3,"report":true}'
```

CLI：

```powershell
cd D:\JavaProjects\ai-faultlab\faultlab-ai-service
.\.venv\Scripts\python.exe scripts\evaluate_retrieval.py --retriever bm25 --top-k 3
.\.venv\Scripts\python.exe scripts\evaluate_retrieval.py --retriever all --top-k 3 --report
.\.venv\Scripts\python.exe scripts\evaluate_retrieval.py --retriever all --top-k 3 --report --output evaluation/reports/rag_eval_report.md
```

指标说明：

- Hit@K：topK 中只要命中任意 expected `docId + section`，该 case hit=true。
- Recall@K：topK 命中的 expected refs 数量 / expected refs 总数。
- MRR：第一个命中的 expected ref 的倒数排名；第一位命中为 1，第二位命中为 0.5，未命中为 0。

演示时重点不是追求满分，而是说明它可以量化：

- 某类故障是否召回了正确 Runbook section。
- Hybrid 是否比单路召回更稳。
- 调参或改 Runbook 后是否退化。

## 9. 5 分钟演示顺序

1. 介绍整体架构：Java 产生 Evidence，Python AI Service 负责 RAG、Prompt 和 LLM 诊断。
2. 展示 Runbook 文档：`faultlab-ai-service/runbooks/` 下三个 Markdown，每个按 section 组织。
3. 执行索引：调用 `/ai/runbooks/index`，展示首次 indexed、再次 skipped、forceRebuild。
4. 执行故障诊断：用 MQ_BACKLOG 跑实验、规则诊断和 AI 诊断。
5. 展示 Runbook References：说明引用来自本次召回，不允许模型编造。
6. 展示 Evaluation Report：运行 `evaluate_retrieval.py --retriever all --top-k 3 --report`。
7. 说明工程取舍：当前是 v0.5 demo，重在链路闭环、可降级、可评测，不是生产级知识平台。

## 10. 常见问题排查

### Milvus 未启动

现象：

- `/ai/runbooks/index` 失败。
- `milvus` retriever 评测返回错误。
- Hybrid 诊断日志中出现 vector retrieval failed。

排查：

```powershell
cd D:\JavaProjects\ai-faultlab\deploy
docker compose ps
docker compose logs -f milvus-standalone
```

处理：

```powershell
docker compose start milvus-standalone
```

### DASHSCOPE_API_KEY 未配置

现象：

- embedding 失败。
- LLM 诊断进入 fallback。

处理：

```powershell
setx DASHSCOPE_API_KEY "your-real-api-key"
```

重新打开终端后再启动 AI Service。

### embedding 失败

可能原因：

- API Key 不正确。
- 网络无法访问百炼 OpenAI-compatible endpoint。
- 请求超时。

处理：

- 检查 `DASHSCOPE_API_KEY`。
- 检查网络。
- 重试 `/ai/runbooks/index`。

### collection 维度不一致

现象：

- Milvus upsert 或 search 报 dimension mismatch。

处理：

- 当前默认 `embedding_dimension = 1024`。
- 确认 Milvus collection schema 与配置一致。
- 必要时删除旧 collection 后重新索引。

### indexedCount=0 skippedCount=3 是否正常

正常。说明三个 Runbook 文档 hash 未变化，索引治理跳过了重复写入。

### runbook_index_state.json 与 Milvus 数据不一致

可能原因：

- 手动清理过 Milvus collection。
- 删除了 `data/runbook_index_state.json`。
- 修改过 collection 配置。

处理：

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/ai/runbooks/index" -ContentType "application/json" -Body '{"forceRebuild":true}'
```

### PowerShell 请求 body 缺失导致 422

如果接口需要 JSON body，PowerShell 中要显式带 `-ContentType "application/json"` 和 `-Body`：

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/ai/runbooks/evaluate" -ContentType "application/json" -Body '{"retriever":"bm25","topK":3}'
```

### 日志看不到 Hybrid 细节

确认：

- 调用的是诊断接口或 `retriever=hybrid` 的评测。
- AI Service 进程是当前代码启动的。
- 日志级别能输出 `INFO` / `WARNING`。

可关注关键词：

- `HybridRunbookRetriever calling vector retriever`
- `HybridRunbookRetriever calling bm25 retriever`
- `HybridRunbookRetriever RRF fusion fused result count`
- `LightweightRunbookReranker final chunk`
