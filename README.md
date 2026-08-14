# AI FaultLab

AI FaultLab is a learning-oriented Java backend fault simulation and AI diagnosis demo. It simulates common backend failure patterns, collects Metrics and Trace spans, runs rule-based diagnosis, retrieves Runbook context, and generates a structured AI diagnosis report.

Current demo scope:

- Basic backend stability: `MQ_BACKLOG`, `THREAD_POOL_SATURATION`, `IDEMPOTENCY_CONFLICT`
- Cache failures: `CACHE_PENETRATION`, `CACHE_BREAKDOWN`, `CACHE_AVALANCHE`
- Database bottlenecks: `DB_SLOW_QUERY`, `DB_LOCK_CONTENTION`, `DB_CONNECTION_POOL_EXHAUSTION`
- Downstream resilience: `DOWNSTREAM_TIMEOUT`, `RETRY_STORM`, `CIRCUIT_BREAKER_OPEN`

Demo entry:

- [AI FaultLab Demo Guide](docs/demo-guide.md)
- [Screenshots Guide](docs/screenshots.md)
- Local frontend: `http://localhost:5173`

Quick local start:

```powershell
# 1. Start infrastructure from WSL
cd /mnt/d/JavaProjects/ai-faultlab/deploy
docker compose up -d

# 2. Start Java backend from PowerShell
cd D:\JavaProjects\ai-faultlab\faultlab-backend
mvn.cmd spring-boot:run

# 3. Start Python AI Service from PowerShell
cd D:\JavaProjects\ai-faultlab\faultlab-ai-service
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000

# 4. Start frontend from PowerShell
cd D:\JavaProjects\ai-faultlab\faultlab-frontend
npm.cmd run dev
```

v0.12.0 focuses on Frontend Demo Polish: the dashboard, experiment overview, metrics summary, Trace view, rule diagnosis, AI report, and demo explanation flow are optimized for clearer walkthroughs. v0.11.0 was the last planned batch of new fault scenarios; later work should focus on presentation, documentation, and deployment hardening rather than adding more scenario types.

AI FaultLab 是一个面向 Java 后端故障排查场景的故障演练与智能诊断平台。

它不是简单的大模型套壳，而是通过真实故障演练、指标采集、Trace 追踪、规则诊断、Evidence Package 和 LLM 诊断报告生成，形成一条可解释、可降级的诊断链路。

当前版本：

```text
v0.3.0: LLM Diagnosis Demo Version
```

当前能力边界：

```text
故障演练 + Trace + 规则诊断 + Evidence Package + ModelRouter + 百炼 LLM + 前端展示
```

## 当前核心能力

- MQ 消息堆积演练
- 线程池饱和演练
- 幂等冲突演练
- Metrics 指标采集
- 自研轻量级 Trace
- TraceContext 跨线程 / MQ Header 传播
- 基于指标的规则诊断
- Java 后端 Evidence Package 组装
- Python AI Service 接入阿里云百炼 OpenAI 兼容接口
- ModelRouter 基础模型路由
- LLM JSON 解析校验
- AI fallback 降级报告
- Vue Dashboard 展示实验、指标、Trace、规则诊断和 AI 诊断报告

## 完整诊断链路

```text
故障演练
  -> Metrics / Trace
  -> 规则诊断
  -> Evidence Package
  -> ModelRouter
  -> Python AI Service
  -> 百炼 LLM
  -> JSON 校验
  -> fallback
  -> Java 落库
  -> 前端展示
```

## 技术栈

后端：

- Java 17
- Spring Boot 3
- MyBatis-Plus
- MySQL
- Redis
- RabbitMQ
- JUnit5
- Mockito

AI Service：

- Python
- FastAPI
- Pydantic
- OpenAI SDK
- 阿里云百炼
- pytest

前端：

- Vue3
- Vite
- 原生 CSS
- `fetch`

部署 / 本地环境：

- Docker
- Docker Compose

## 项目结构

```text
ai-faultlab
├── faultlab-backend       # Java Spring Boot 后端
├── faultlab-ai-service    # Python FastAPI AI 诊断服务
├── faultlab-frontend      # Vue3 + Vite Dashboard
├── faultlab-runbook       # Runbook 文档目录，后续 RAG 使用
├── faultlab-trace-sdk     # 轻量 Trace SDK
├── deploy                 # Docker Compose 和部署文件
├── docs                   # 项目文档
└── README.md
```

## 本地启动

### 1. 启动 MySQL / Redis / RabbitMQ

```bash
cd deploy
docker compose up -d
docker compose ps
```

正常应看到：

- `faultlab-mysql`
- `faultlab-redis`
- `faultlab-rabbitmq`

RabbitMQ 管理台：

```text
http://localhost:15672
```

### 2. 启动 Java Backend

推荐使用 IDEA 启动：

```text
FaultLabBackendApplication
```

本地环境变量示例：

```text
MYSQL_HOST=localhost;MYSQL_PORT=3306;MYSQL_DATABASE=faultlab;MYSQL_USERNAME=faultlab;MYSQL_PASSWORD=faultlab123456;RABBITMQ_HOST=localhost;RABBITMQ_PORT=5672;RABBITMQ_USERNAME=faultlab;RABBITMQ_PASSWORD=faultlab123456;REDIS_HOST=localhost;REDIS_PORT=6379
```

AI Service 连接配置已在 `faultlab-backend/src/main/resources/application.yml` 中提供默认值：

```text
AI_SERVICE_BASE_URL=http://localhost:8000
AI_SERVICE_DIAGNOSIS_PATH=/ai/diagnosis/generate
AI_SERVICE_READ_TIMEOUT_MS=120000
```

健康检查：

```text
GET http://localhost:8080/api/health
```

### 3. 配置 DASHSCOPE_API_KEY

只有 `DASHSCOPE_API_KEY` 是必须配置的敏感环境变量。其他 LLM 运行参数在 `faultlab-ai-service/app/config.py` 中有代码默认值。

PowerShell 示例：

```powershell
setx DASHSCOPE_API_KEY "你的真实百炼APIKey"
```

重新打开 PowerShell 后确认：

```powershell
echo $env:DASHSCOPE_API_KEY
```

不要提交真实 `.env`、API Key 或私有凭据。

### 4. 启动 Python AI Service

```bash
cd faultlab-ai-service
pip install -r requirements.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

健康检查：

```text
GET http://localhost:8000/ai/health
```

未配置 `DASHSCOPE_API_KEY` 时，AI Service 会自动返回 `fallback=true` 的降级报告，不会调用 LLM。

### 5. 启动前端

```bash
cd faultlab-frontend
npm install
npm.cmd run dev
```

访问：

```text
http://localhost:5173
```

## 常用接口

Java Backend：

- `GET /api/health`
- `POST /api/experiments/start`
- `GET /api/experiments/{experimentId}`
- `GET /api/experiments/{experimentId}/metrics`
- `GET /api/traces/{traceId}`
- `POST /api/diagnosis/{experimentId}/rule`
- `POST /api/diagnosis/{experimentId}/ai/generate`
- `GET /api/diagnosis/{experimentId}`

Python AI Service：

- `GET /ai/health`
- `POST /ai/diagnosis/generate`
- `POST /ai/runbooks/evaluate`

## v0.3 演示流程

1. 启动 Docker Compose。
2. 启动 Java Backend。
3. 设置 `DASHSCOPE_API_KEY`。
4. 启动 Python AI Service。
5. 启动 Vue 前端。
6. 打开 Dashboard：`http://localhost:5173`。
7. 选择 `MQ_BACKLOG`。
8. 使用默认参数或设置 `messageCount=10`、`consumerDelayMs=1000`。
9. 点击“开始演练”。
10. 点击“执行规则诊断”。
11. 点击“生成 AI 诊断报告”。
12. 查看 Metrics、Trace 树、Rule Diagnosis 和 AI Report。
13. 切换 `THREAD_POOL_SATURATION`、`IDEMPOTENCY_CONFLICT` 重复演示。

## ModelRouter

Python AI Service 当前包含基础模型路由能力。路由逻辑在 `faultlab-ai-service/app/model_router.py` 中：

- `ruleResult` 缺失或未命中：使用 fast model
- Trace 节点数较多：使用 reasoning model
- 规则证据较丰富：使用 reasoning model
- Metrics 数量较多：使用 long context model
- 其他情况：使用 default model

当前模型名称以 `faultlab-ai-service/app/config.py` 为准。调整模型时只修改该文件，不需要新增环境变量。

## 已知限制 / TODO

当前还没有：

- Runbook RAG
- Milvus 向量检索
- Tool Calling
- LangGraph
- MCP
- Runbook 管理后台
- 生产级鉴权
- 完整监控告警

## Milvus Runbook Retrieval

The local Docker Compose stack includes Milvus standalone:

- `faultlab-milvus-etcd`
- `faultlab-milvus-minio`
- `faultlab-milvus-standalone`

Start infrastructure:

```bash
cd deploy
docker compose up -d
docker compose ps
```

Milvus is exposed on:

```text
localhost:19530
```

Before using vector Runbook retrieval, configure `DASHSCOPE_API_KEY`, start `faultlab-ai-service`, and build the Runbook index:

```text
POST http://localhost:8000/ai/runbooks/index
```

The request body is optional. Use `{"forceRebuild": true}` to force a full rebuild.

The AI service embeds local Markdown Runbook chunks with Alibaba Cloud Bailian `text-embedding-v4` at dimension `1024` and writes them to Milvus collection `faultlab_runbook_chunks`. Diagnosis retrieval uses Hybrid Retrieval Basic: Milvus vector retrieval + BM25-like keyword retrieval + Reciprocal Rank Fusion + lightweight rule-based rerank. Index governance stores document state in `faultlab-ai-service/data/runbook_index_state.json`, compares Markdown content hashes, skips unchanged documents, deletes old chunks when content changes, and removes stale chunks when a Runbook file is deleted. If Milvus retrieval or embedding fails, diagnosis continues with BM25-like keyword retrieval. If the hybrid retriever itself fails, the service falls back to `KeywordRunbookRetriever`.

Common issues:

- Milvus not started: `MilvusRunbookRetriever` fails and diagnosis continues with BM25-like keyword retrieval.
- Collection missing: call `POST /ai/runbooks/index`.
- Unchanged documents skipped: this is expected when content hashes match the state file.
- Need full rebuild: call `POST /ai/runbooks/index` with `{"forceRebuild": true}`.
- Embedding timeout: retry indexing and check Bailian network/API availability.
- `DASHSCOPE_API_KEY` missing: embedding and LLM calls cannot run; diagnosis uses fallback where applicable.
- Vector dimension mismatch: confirm `settings.embedding_dimension` matches the Milvus collection schema, then recreate the collection if needed.

Current limits: no MySQL index state table, no management UI, no scheduled scan, no async indexing queue, no rollback, and no dedicated rerank model. The keyword retriever is BM25-like and dependency-free, not a full search-engine BM25 implementation. The reranker is rule-based and does not call a rerank model.

## RAG Retrieval Evaluation

`faultlab-ai-service` includes a basic RAG retrieval evaluation runner for measuring Runbook retrieval quality without calling the LLM.

Dataset:

```text
faultlab-ai-service/evaluation/rag_eval_cases.json
```

The evaluation dataset now contains 72 cases, expanded from the original 9-case baseline. It covers MQ backlog, thread pool saturation, idempotency conflict, cache failure, database bottleneck, and downstream resilience situations while keeping expected references strict at `docId + section`.

Metrics:

- Hit@K
- Recall@K
- MRR

Evaluation API:

```text
POST http://localhost:8000/ai/runbooks/evaluate
body: {"retriever": "hybrid", "topK": 3}
```

Supported retrievers are `bm25`, `hybrid`, `milvus`, and `all`. BM25 mode reads local Markdown runbooks only. Hybrid and Milvus require vector retrieval dependencies to be available; if a retriever fails during `all`, the response includes that retriever's error and continues evaluating the others.

Command line:

```bash
cd faultlab-ai-service
python scripts/evaluate_retrieval.py --retriever hybrid --top-k 3
python scripts/evaluate_retrieval.py --retriever all --top-k 3
python scripts/evaluate_retrieval.py --retriever all --top-k 3 --report
python scripts/evaluate_retrieval.py --retriever all --top-k 3 --report --output evaluation/reports/rag_eval_report.md
```

Markdown reports include Overall Metrics, Retriever Comparison, Metrics By Fault Type, Case Details, Miss Cases, and Optimization Suggestions.

The API can also include a Markdown report while still returning `application/json`:

```text
POST http://localhost:8000/ai/runbooks/evaluate
body: {"retriever": "all", "topK": 3, "report": true}
```

The report suggestions are rule-based and do not call the LLM. After expanding the dataset, retrieval metrics may decrease because the baseline is stricter; this should be read as improved evaluation coverage, not necessarily system degradation. Follow-up tuning should use miss cases to improve Runbook keywords, query construction, BM25-like scoring, and rerank weights. Future extensions can add nDCG, retrieval result visualization, and stricter CI regression thresholds.

### RAG Evaluation Regression Gate

`faultlab-ai-service` includes a retrieval regression gate for PR and local checks. Thresholds live in:

```text
faultlab-ai-service/evaluation/rag_eval_thresholds.json
```

These thresholds are the v0.5 baseline and are intentionally modest; they can be raised later as the Runbook dataset and retrieval implementation mature. The first CI gate runs BM25-only retrieval so it does not require Milvus, embeddings, or `DASHSCOPE_API_KEY`.

Local command:

```powershell
cd faultlab-ai-service
.\.venv\Scripts\python.exe scripts\check_rag_regression.py --retriever bm25
```

Hybrid and Milvus evaluation can still be run locally, but they depend on Milvus and embedding availability. If the regression gate fails, check whether Runbook keywords or section titles were weakened, query construction lost key fields, BM25-like scoring regressed, or topK/rerank/RRF parameters were changed by mistake.

### RAG Retrieval Debug

The AI service also exposes a retrieval-only debug path for inspecting one query across vector retrieval, BM25-like retrieval, RRF fusion, rerank, and final topK selection. It does not call the LLM and does not modify the diagnosis API.

API:

```text
POST /ai/runbooks/retrieve/debug
```

CLI:

```bash
cd faultlab-ai-service
python scripts/debug_retrieval.py --case-id mq_backlog_core_metrics --top-k 3
python scripts/debug_retrieval.py --case-id mq_backlog_core_metrics --top-k 3 --no-content
```

The debug response includes `queryText`, `vectorResults`, `bm25Results`, `fusionResults`, `rerankResults`, `finalResults`, and `warnings`. Use it to analyze miss cases, debug query construction, compare Milvus and BM25-like recall, and inspect RRF/rerank ordering changes. BM25 debug works without Milvus; vector debug requires Milvus and embeddings to be available.

### Runbook Management and Index Task Basic

`faultlab-ai-service` now includes local Markdown Runbook management APIs and a synchronous index task record.

Runbook APIs:

```text
GET /ai/runbooks
GET /ai/runbooks/{docId}
POST /ai/runbooks
PUT /ai/runbooks/{docId}
DELETE /ai/runbooks/{docId}
```

Index task APIs:

```text
POST /ai/runbooks/index-tasks
GET /ai/runbooks/index-tasks
GET /ai/runbooks/index-tasks/{taskId}
```

The existing `POST /ai/runbooks/index` remains compatible and now also returns `taskId`. Task records are stored locally in `faultlab-ai-service/data/runbook_index_tasks.json`, which is ignored by Git. This is still local Markdown plus JSON state, not MySQL-backed Runbook management, not an async indexing queue, and not an approval or permission system.

## RAG v0.5 Documentation

当前 RAG 阶段定位为 `v0.5 RAG Demo`，重点是把 Runbook 索引、Hybrid Retrieval、Prompt 注入、引用校验和检索评测串成闭环。它不是生产级 Runbook 管理平台。

文档入口：

- [RAG v0.5 Demo Guide](docs/rag-v0.5-demo-guide.md)：本地演示流程、索引演示、诊断演示、fallback 和评测报告演示。
- [RAG Architecture](docs/rag-architecture.md)：总体架构、离线索引、在线检索、fallback 和关键模块职责。
- [RAG Evaluation Guide](docs/rag-evaluation-guide.md)：评测数据集、Hit@K / Recall@K / MRR、API/CLI 用法和报告解读。
- [RAG Interview Guide](docs/rag-interview-guide.md)：面试口述版本、常见追问、简历写法和项目亮点总结。

当前 RAG v0.5 能力摘要：

- Runbook Markdown section chunking。
- 百炼 `text-embedding-v4` + Milvus vector retrieval。
- BM25-like keyword retrieval，不是标准搜索引擎级 BM25。
- RRF fusion + lightweight rule-based rerank，不是真实 rerank 模型。
- content hash、forceRebuild、旧 chunk 清理等基础索引治理。
- Prompt 注入 Runbook Context，服务端校验 `runbookReferences`。
- RAG Evaluation 支持 Hit@K、Recall@K、MRR 和 Markdown Report。

## License

MIT

## v0.9.0 Cache Failure Scenarios

The Java backend supports three cache failure drill scenario codes through the existing `POST /api/experiments/start` flow:

- `CACHE_PENETRATION`: nonexistent keys miss Redis and DB, increasing DB query volume.
- `CACHE_BREAKDOWN`: a hot key expires and concurrent requests repeatedly rebuild cache from DB.
- `CACHE_AVALANCHE`: many keys expire together, or Redis is simulated as unavailable, pushing traffic to DB or fallback.

Example requests:

```json
{
  "scenarioCode": "CACHE_PENETRATION",
  "params": {
    "requestCount": 100,
    "invalidKeyRatio": 0.8,
    "enableNullCache": false,
    "enableBloomFilter": false
  }
}
```

```json
{
  "scenarioCode": "CACHE_BREAKDOWN",
  "params": {
    "requestCount": 100,
    "hotKey": "hot:item:1",
    "concurrency": 20,
    "enableMutex": false,
    "enableLogicalExpire": false
  }
}
```

```json
{
  "scenarioCode": "CACHE_AVALANCHE",
  "params": {
    "keyCount": 50,
    "requestCount": 200,
    "sameTtl": true,
    "enableTtlJitter": false,
    "simulateRedisDown": false,
    "enableFallback": false
  }
}
```

These scenarios generate FaultMetric records, Trace spans, rule diagnosis results, and EvidencePackage input for the existing AI diagnosis chain. They simulate cache behavior internally and use the `faultlab:` key prefix; they do not flush Redis or add dependencies.

## v0.9.0 Cache Runbooks and RAG Evaluation

The Python AI service now includes cache failure Runbooks for:

- `CACHE_PENETRATION`
- `CACHE_BREAKDOWN`
- `CACHE_AVALANCHE`

The v0.9 cache expansion moved the RAG retrieval evaluation dataset from 27 to 42 cases, adding 5 strict `docId + section` cases for each cache fault type. The current v0.11 dataset is 72 cases after database bottleneck and downstream resilience additions. Evaluation still measures retrieval only; it does not call the LLM, Java backend, or frontend. BM25-like retrieval remains a lightweight dependency-free scorer, not a standard search-engine BM25 implementation. The lightweight reranker is still rule-based and is not a real rerank model. Hybrid and Milvus evaluation still depend on local Milvus and embedding/index availability.

## v0.10.0 Database Runbooks and RAG Evaluation

The Python AI service now includes database bottleneck Runbooks for:

- `DB_SLOW_QUERY`
- `DB_LOCK_CONTENTION`
- `DB_CONNECTION_POOL_EXHAUSTION`

The RAG retrieval evaluation dataset expands from 42 to 57 cases, adding 5 strict `docId + section` cases for each database fault type. Evaluation still measures retrieval only; BM25-like retrieval remains a lightweight dependency-free scorer, not a standard BM25 implementation. The lightweight reranker is still rule-based and is not a real rerank model. Hybrid and Milvus evaluation still depend on local Milvus, embedding service availability, and a current vector index.

## v0.11.0 Downstream Runbooks and RAG Evaluation

The Python AI service now includes downstream resilience Runbooks for:

- `DOWNSTREAM_TIMEOUT`
- `RETRY_STORM`
- `CIRCUIT_BREAKER_OPEN`

The RAG retrieval evaluation dataset expands from 57 to 72 cases, adding 5 strict `docId + section` cases for each downstream fault type. Evaluation still measures retrieval only; BM25-like retrieval remains a lightweight dependency-free scorer, not a standard BM25 implementation. The lightweight reranker is still rule-based and is not a real rerank model. Hybrid and Milvus evaluation still depend on local Milvus, embedding service availability, and a current vector index. v0.11.0 is the last planned new fault-scenario RAG case batch; later work shifts toward frontend refinement, demo presentation, README updates, and server deployment.

## v0.10.0 Database Bottleneck Scenarios

The Java backend supports three deterministic database bottleneck drill scenario codes through the existing `POST /api/experiments/start` flow:

- `DB_SLOW_QUERY`: simulates full scan / slow SQL metrics without creating a large table.
- `DB_LOCK_CONTENTION`: simulates hotspot row lock waits without opening dangerous real long transactions.
- `DB_CONNECTION_POOL_EXHAUSTION`: simulates connection acquire waits and timeouts without changing HikariCP or exhausting real DB connections.

Example requests:

```json
{
  "scenarioCode": "DB_SLOW_QUERY",
  "params": {
    "requestCount": 100,
    "queryMode": "FULL_SCAN",
    "tableSize": 100000,
    "scannedRows": 80000,
    "dbDelayMs": 80,
    "enableIndexOptimization": false
  }
}
```

```json
{
  "scenarioCode": "DB_LOCK_CONTENTION",
  "params": {
    "requestCount": 50,
    "concurrency": 10,
    "targetRowId": "order:1",
    "lockHoldMs": 200,
    "lockWaitTimeoutMs": 100,
    "enableShortTransaction": false
  }
}
```

```json
{
  "scenarioCode": "DB_CONNECTION_POOL_EXHAUSTION",
  "params": {
    "requestCount": 100,
    "concurrency": 30,
    "maxPoolSize": 10,
    "queryDelayMs": 200,
    "connectionAcquireTimeoutMs": 50,
    "enableFastRelease": false
  }
}
```

These scenarios generate FaultMetric records, Trace child spans, rule diagnosis results, and EvidencePackage input for the existing AI diagnosis chain. They do not add dependencies, change API paths, modify the frontend, or modify the Python AI service.

## v0.11.0 Downstream Resilience Scenarios

The Java backend supports three deterministic downstream resilience drill scenario codes through the existing `POST /api/experiments/start` flow:

- `DOWNSTREAM_TIMEOUT`: simulates slow downstream responses exceeding caller timeout and optional fallback.
- `RETRY_STORM`: simulates downstream failures causing retry amplification and retry exhaustion.
- `CIRCUIT_BREAKER_OPEN`: simulates a local circuit breaker opening after high failure or slow-call rate.

Example requests:

```json
{
  "scenarioCode": "DOWNSTREAM_TIMEOUT",
  "params": {
    "requestCount": 100,
    "concurrency": 20,
    "downstreamDelayMs": 300,
    "timeoutMs": 100,
    "timeoutRatio": 0.8,
    "enableFallback": false,
    "fallbackDelayMs": 10
  }
}
```

```json
{
  "scenarioCode": "RETRY_STORM",
  "params": {
    "requestCount": 100,
    "concurrency": 20,
    "failureRatio": 0.7,
    "maxRetries": 3,
    "retryBackoffMs": 20,
    "enableRetryLimit": false,
    "enableJitter": false
  }
}
```

```json
{
  "scenarioCode": "CIRCUIT_BREAKER_OPEN",
  "params": {
    "requestCount": 100,
    "failureRatio": 0.8,
    "slowCallRatio": 0.5,
    "slidingWindowSize": 20,
    "failureRateThreshold": 0.5,
    "slowCallThresholdMs": 200,
    "openDurationMs": 500,
    "enableFallback": true
  }
}
```

These scenarios generate FaultMetric records, Trace child spans, rule diagnosis results, and EvidencePackage input for the existing AI diagnosis chain. They do not add dependencies, make real downstream calls, change API paths, modify the frontend, or modify the Python AI service.
