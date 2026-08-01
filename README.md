# AI FaultLab

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

The report suggestions are rule-based and do not call the LLM. Future extensions can add more cases, nDCG, retrieval result visualization, and CI regression evaluation.

## License

MIT
