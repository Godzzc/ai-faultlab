# Local Development

本文档记录 AI FaultLab v0.3.0 的本地开发启动和排查命令。完整演示流程见 [demo-guide.md](./demo-guide.md)。

## Docker Compose

启动基础组件：

```bash
cd deploy
docker compose up -d
docker compose ps
```

正常应启动：

- `faultlab-mysql`
- `faultlab-redis`
- `faultlab-rabbitmq`

查看日志：

```bash
docker compose logs -f mysql
docker compose logs -f rabbitmq
```

停止容器但保留数据卷：

```bash
docker compose down
```

停止容器并清理数据卷：

```bash
docker compose down -v
```

区别：

- `docker compose down`：删除容器和网络，保留 MySQL / Redis / RabbitMQ 数据卷。
- `docker compose down -v`：同时删除数据卷。下次启动时 MySQL 会重新执行初始化脚本，适合 schema 变更后重建本地库。

## Java Backend

推荐使用 IDEA 启动：

```text
FaultLabBackendApplication
```

IDEA Environment variables 示例：

```text
MYSQL_HOST=localhost;MYSQL_PORT=3306;MYSQL_DATABASE=faultlab;MYSQL_USERNAME=faultlab;MYSQL_PASSWORD=faultlab123456;RABBITMQ_HOST=localhost;RABBITMQ_PORT=5672;RABBITMQ_USERNAME=faultlab;RABBITMQ_PASSWORD=faultlab123456;REDIS_HOST=localhost;REDIS_PORT=6379
```

AI Service 相关配置在 `faultlab-backend/src/main/resources/application.yml` 中已有默认值：

```yaml
faultlab:
  ai-service:
    base-url: ${AI_SERVICE_BASE_URL:http://localhost:8000}
    diagnosis-path: ${AI_SERVICE_DIAGNOSIS_PATH:/ai/diagnosis/generate}
    connect-timeout-ms: ${AI_SERVICE_CONNECT_TIMEOUT_MS:3000}
    read-timeout-ms: ${AI_SERVICE_READ_TIMEOUT_MS:120000}
```

`AI_SERVICE_READ_TIMEOUT_MS` 默认建议为 `120000`，避免本地 LLM 调用较慢时 Java 过早超时。

健康检查：

```text
GET http://localhost:8080/api/health
```

命令行启动：

```bash
cd faultlab-backend
mvn spring-boot:run
```

## Python AI Service

只需要配置 `DASHSCOPE_API_KEY`：

```powershell
setx DASHSCOPE_API_KEY "你的真实百炼APIKey"
```

重新打开 PowerShell 后启动：

```powershell
cd faultlab-ai-service
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

如果还没有虚拟环境：

```powershell
cd faultlab-ai-service
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

`.venv` 不要提交到 Git。

健康检查：

```text
GET http://localhost:8000/ai/health
```

未配置 `DASHSCOPE_API_KEY` 时，AI Service 会自动返回 fallback 报告。

## RabbitMQ Management

管理台地址：

```text
http://localhost:15672
```

默认本地账号密码通常来自 `deploy/.env.example`：

```text
faultlab / faultlab123456
```

如果复制后的 `deploy/.env` 修改过账号密码，Java Backend 环境变量也需要同步。

## Frontend

启动前端：

```bash
cd faultlab-frontend
npm install
npm.cmd run dev
```

访问：

```text
http://localhost:5173
```

Vite 代理：

```text
/api -> http://localhost:8080
```

构建验证：

```bash
npm.cmd run build
```

## Milvus Runbook Index

Docker Compose starts Milvus standalone together with MySQL, Redis, and RabbitMQ:

- `faultlab-milvus-etcd`
- `faultlab-milvus-minio`
- `faultlab-milvus-standalone`

Start and verify:

```bash
cd deploy
docker compose up -d
docker compose ps
```

Milvus listens on:

```text
localhost:19530
```

After setting `DASHSCOPE_API_KEY` and starting the Python AI Service, build the Runbook vector index:

```text
POST http://localhost:8000/ai/runbooks/index
```

Expected response:

```json
{
  "status": "success",
  "collectionName": "faultlab_runbook_chunks",
  "indexedCount": 12,
  "skippedCount": 0,
  "deletedCount": 0,
  "failedCount": 0,
  "indexedDocuments": ["mq-backlog"],
  "skippedDocuments": [],
  "failedDocuments": [],
  "forceRebuild": false
}
```

The request body is optional:

```json
{
  "forceRebuild": false
}
```

Index governance stores runtime state in `faultlab-ai-service/data/runbook_index_state.json`. The service calculates each Markdown file content hash, skips unchanged documents, deletes old Milvus chunks when a document changes, and removes old chunks when a Runbook Markdown file is deleted. Use `{"forceRebuild": true}` to force a full rebuild.

Diagnosis retrieval uses Hybrid Retrieval Basic. `RetrievalService` calls `HybridRunbookRetriever`, which runs Milvus vector retrieval and BM25-like local Markdown retrieval, fuses both result lists with Reciprocal Rank Fusion, and applies a lightweight rule-based rerank before returning the final topK Runbook chunks.

If Milvus is unavailable, the collection does not exist, or embedding fails, Hybrid retrieval still returns BM25-like keyword results. If the Hybrid retriever itself fails, `RetrievalService` falls back to `KeywordRunbookRetriever`.

Common issues:

- Milvus not started: run `docker compose ps` and check `docker compose logs -f milvus-standalone`.
- Collection missing: call `POST /ai/runbooks/index`.
- Unchanged documents skipped: expected when content hash matches the state file.
- Force full rebuild: call `POST /ai/runbooks/index` with `{"forceRebuild": true}`.
- Embedding timeout: verify `DASHSCOPE_API_KEY` and network access to Bailian.
- `DASHSCOPE_API_KEY` missing: indexing fails and LLM diagnosis will use fallback.
- Vector dimension mismatch: recreate the Milvus collection after changing `embedding_dimension`.
- Hybrid logs should include vector result count, BM25 result count, fused result count, and final result count.

Current limits: no MySQL index state table, no management UI, no scheduled scan, no async indexing queue, no rollback, and no dedicated rerank model. The keyword scoring is BM25-like and dependency-free, not a full search-engine BM25 implementation. The reranker is rule-based and does not call a rerank model.

Do not commit `faultlab-ai-service/data/runbook_index_state.json`; it is generated at runtime.

## RAG Retrieval Evaluation

The Python AI Service includes a retrieval-only evaluation runner for Runbook RAG. It does not call the LLM, Java backend, or frontend.

Dataset:

```text
faultlab-ai-service/evaluation/rag_eval_cases.json
```

Metrics:

- Hit@K
- Recall@K
- MRR

Start the AI Service:

```powershell
cd faultlab-ai-service
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Call the evaluation endpoint:

```text
POST http://localhost:8000/ai/runbooks/evaluate
body: {"retriever": "bm25", "topK": 3}
```

Other supported request bodies:

```text
{"retriever": "hybrid", "topK": 3}
{"retriever": "all", "topK": 3}
{"retriever": "all", "topK": 3, "report": true}
```

Run from the command line:

```powershell
cd faultlab-ai-service
.\.venv\Scripts\python.exe scripts\evaluate_retrieval.py --retriever bm25 --top-k 3
.\.venv\Scripts\python.exe scripts\evaluate_retrieval.py --retriever all --top-k 3 --report
.\.venv\Scripts\python.exe scripts\evaluate_retrieval.py --retriever all --top-k 3 --report --output evaluation/reports/rag_eval_report.md
```

Markdown reports include Overall Metrics, Retriever Comparison, Metrics By Fault Type, Case Details, Miss Cases, and Optimization Suggestions. API report responses keep `application/json` and add `markdownReport`.

BM25 evaluation uses local Markdown only. Hybrid and Milvus evaluation need Milvus and embedding dependencies; when a retriever fails in `all`, the report includes that retriever's error and continues with the rest. Report suggestions are rule-based and do not call the LLM. Current limits: no nDCG, no visualization UI, and no CI regression gate.

## RAG v0.5 Demo

完整演示文档见 [RAG v0.5 Demo Guide](./rag-v0.5-demo-guide.md)。架构说明见 [RAG Architecture](./rag-architecture.md)，评测说明见 [RAG Evaluation Guide](./rag-evaluation-guide.md)，面试复盘见 [RAG Interview Guide](./rag-interview-guide.md)。

本地演示建议顺序：

1. 启动 Docker Compose，确认 MySQL、Redis、RabbitMQ、Milvus 均启动。
2. 启动 Java Backend、Python AI Service 和 Vue Frontend。
3. 配置 `DASHSCOPE_API_KEY`。
4. 调用 `/ai/runbooks/index` 构建 Runbook 索引。
5. 再次调用 `/ai/runbooks/index`，确认 `indexedCount=0`、`skippedCount>0`。
6. 使用 `{"forceRebuild":true}` 演示强制重建。
7. 在前端运行 `MQ_BACKLOG` 实验，执行规则诊断并生成 AI 诊断报告。
8. 查看 AI Report 中的 `runbookReferences`。
9. 运行 retrieval evaluation 并输出 Markdown report。
10. 停止 `milvus-standalone`，再次诊断，验证 keyword-only fallback 不让诊断接口直接 500。

索引命令：

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/ai/runbooks/index" -ContentType "application/json" -Body '{}'
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/ai/runbooks/index" -ContentType "application/json" -Body '{"forceRebuild":true}'
```

评测命令：

```powershell
cd faultlab-ai-service
.\.venv\Scripts\python.exe scripts\evaluate_retrieval.py --retriever bm25 --top-k 3
.\.venv\Scripts\python.exe scripts\evaluate_retrieval.py --retriever all --top-k 3 --report
.\.venv\Scripts\python.exe scripts\evaluate_retrieval.py --retriever all --top-k 3 --report --output evaluation/reports/rag_eval_report.md
```

API 评测并返回 Markdown report：

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8000/ai/runbooks/evaluate" -ContentType "application/json" -Body '{"retriever":"all","topK":3,"report":true}'
```

Milvus fallback 演示：

```powershell
cd deploy
docker compose stop milvus-standalone
# 再次调用诊断，观察 AI Service 日志中的 vector retrieval failure 和 BM25-like fallback。
docker compose start milvus-standalone
```

当前是 v0.5 RAG Demo，不是生产级平台。演示时不要把 BM25-like 描述成标准 BM25，不要把 lightweight rerank 描述成真实 rerank 模型，也不要说已经具备 Runbook 管理后台、可视化评测 UI 或 CI regression gate。

## Notes

- 不要提交 `deploy/.env`、真实 API Key、`.env`、`.venv`。
- MySQL 初始化脚本来自 `faultlab-backend/src/main/resources/db/schema.sql`。
- 修改 schema 后，如果已有 MySQL volume，需要执行 `docker compose down -v` 后重新启动才会重建表结构。
- Python AI Service 的模型、超时和路由配置目前在 `faultlab-ai-service/app/config.py` 中维护。
