# Local Development

本文档说明 AI FaultLab 的本地启动流程、健康检查和常见本地验证命令。完整演示路径见 [Demo Guide](./demo-guide.md)，环境变量说明见 [Environment Variables Guide](./env-guide.md)。

当前项目不提供公网在线 Demo。本地开发和截图采集都应基于完整本地运行环境。

## 1. 启动顺序

推荐按以下顺序启动：

1. 基础设施：MySQL / Redis / RabbitMQ / Milvus
2. Java Backend
3. Python AI Service
4. Frontend

## 2. 启动基础设施

如果在 WSL 中运行 Docker Compose：

```bash
cd /mnt/d/JavaProjects/ai-faultlab/deploy
docker compose up -d
docker compose ps
```

如果在 PowerShell 中运行 Docker Compose：

```powershell
cd D:\JavaProjects\ai-faultlab\deploy
docker compose up -d
docker compose ps
```

正常应看到以下核心容器：

- `faultlab-mysql`
- `faultlab-redis`
- `faultlab-rabbitmq`
- `faultlab-milvus-etcd`
- `faultlab-milvus-minio`
- `faultlab-milvus-standalone`

查看日志：

```bash
docker compose logs -f mysql
docker compose logs -f rabbitmq
docker compose logs -f milvus-standalone
```

停止容器但保留数据卷：

```bash
docker compose down
```

停止容器并清理数据卷：

```bash
docker compose down -v
```

`docker compose down -v` 会删除 MySQL / Redis / RabbitMQ / Milvus 数据卷。只有在需要重建本地数据时再使用。

## 3. 启动 Java Backend

PowerShell：

```powershell
cd D:\JavaProjects\ai-faultlab\faultlab-backend
mvn.cmd spring-boot:run
```

健康检查：

```text
GET http://localhost:8080/api/health
```

本地默认配置来自 `faultlab-backend/src/main/resources/application.yml`。如需覆盖 MySQL、Redis、RabbitMQ 或 AI Service 地址，优先使用环境变量，详情见 [Environment Variables Guide](./env-guide.md)。

## 4. 启动 Python AI Service

如果还没有虚拟环境：

```powershell
cd D:\JavaProjects\ai-faultlab\faultlab-ai-service
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

启动服务：

```powershell
cd D:\JavaProjects\ai-faultlab\faultlab-ai-service
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

健康检查：

```text
GET http://localhost:8000/ai/health
```

如果需要真实 AI Report，需要在本地配置 `DASHSCOPE_API_KEY`。不要提交真实 key。

PowerShell 示例：

```powershell
$env:DASHSCOPE_API_KEY="your_dashscope_api_key_here"
```

未配置 `DASHSCOPE_API_KEY` 时，AI Service 可以启动；诊断链路会在模型不可用时使用 fallback 报告。

## 5. 启动 Frontend

PowerShell：

```powershell
cd D:\JavaProjects\ai-faultlab\faultlab-frontend
npm.cmd install
npm.cmd run dev
```

访问：

```text
http://localhost:5173
```

Vite 本地代理配置位于 `faultlab-frontend/vite.config.js`：

```text
/api -> http://localhost:8080
/ai  -> http://localhost:8000
```

## 6. RabbitMQ Management

本地管理台：

```text
http://localhost:15672
```

默认本地账号密码来自 `deploy/.env.example` 或 Docker Compose 默认值：

```text
faultlab / faultlab123456
```

这些默认值仅用于本地开发。

## 7. Runbook Index

Milvus 启动后，可以构建 Runbook 向量索引：

```text
POST http://localhost:8000/ai/runbooks/index
```

请求体可选：

```json
{
  "forceRebuild": false
}
```

强制重建：

```json
{
  "forceRebuild": true
}
```

索引状态文件是运行时文件，不应提交：

```text
faultlab-ai-service/data/runbook_index_state.json
faultlab-ai-service/data/runbook_index_tasks.json
```

## 8. RAG Retrieval Evaluation

BM25-like retrieval 不依赖 Milvus、embedding API 或 `DASHSCOPE_API_KEY`：

```powershell
cd D:\JavaProjects\ai-faultlab\faultlab-ai-service
.\.venv\Scripts\python.exe scripts\evaluate_retrieval.py --retriever bm25 --top-k 3 --report
```

BM25 regression gate：

```powershell
cd D:\JavaProjects\ai-faultlab\faultlab-ai-service
.\.venv\Scripts\python.exe scripts\check_rag_regression.py --retriever bm25
```

Hybrid 和 Milvus evaluation 依赖本地 Milvus、embedding 可用性和当前 Runbook 索引状态。

## 9. Frontend Build Check

如果改动了前端代码或需要验证构建：

```powershell
cd D:\JavaProjects\ai-faultlab\faultlab-frontend
npm.cmd run build
```

文档改动通常不需要运行前端 build。

## 10. 常见注意事项

- 不要提交 `deploy/.env`、真实 API Key、`.env` 或 `.venv`
- 不要提交 `faultlab-ai-service/data/runbook_index_state.json`
- 不要提交 `faultlab-ai-service/data/runbook_index_tasks.json`
- 不要提交 `faultlab-ai-service/evaluation/reports/*.md`
- 不要提交 `faultlab-ai-service/evaluation/reports/*.json`
- 修改 MySQL schema 后，如果已有旧 volume，可能需要执行 `docker compose down -v` 后重新启动
- Python AI Service 的模型、Milvus 和 retrieval 默认配置目前主要在 `faultlab-ai-service/app/config.py`
