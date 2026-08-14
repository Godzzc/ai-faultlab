# Environment Variables Guide

## 1. 使用原则

- 不提交真实 `.env`
- 只提交 `.env.example`
- API Key、数据库密码、模型密钥应通过环境变量或本地配置文件注入
- GitHub 仓库中不应出现真实密钥
- 本地开发和未来服务器部署要区分

## 2. Java Backend 配置

Java Backend 使用 Spring Boot 配置。当前默认配置位于：

```text
faultlab-backend/src/main/resources/application.yml
```

本地运行时，常用配置可以通过环境变量覆盖。

MySQL：

- `MYSQL_HOST`
- `MYSQL_PORT`
- `MYSQL_DATABASE`
- `MYSQL_USERNAME`
- `MYSQL_PASSWORD`

Redis：

- `REDIS_HOST`
- `REDIS_PORT`
- `REDIS_PASSWORD`

RabbitMQ：

- `RABBITMQ_HOST`
- `RABBITMQ_PORT`
- `RABBITMQ_USERNAME`
- `RABBITMQ_PASSWORD`

AI Service：

- `AI_SERVICE_BASE_URL`
- `AI_SERVICE_DIAGNOSIS_PATH`
- `AI_SERVICE_CONNECT_TIMEOUT_MS`
- `AI_SERVICE_READ_TIMEOUT_MS`

MQ backlog scenario：

- `MQ_BACKLOG_EXCHANGE`
- `MQ_BACKLOG_QUEUE`
- `MQ_BACKLOG_ROUTING_KEY`

如果不设置这些变量，项目会使用 `application.yml` 中的默认值。Docker Compose 本地默认账号密码来自 `deploy/docker-compose.yml` 和 `deploy/.env.example`，仅用于本地开发。

如需通过环境变量覆盖其他 Spring Boot 配置，可参考 Spring Boot 配置命名规则；不要在文档或仓库中编造未实际使用的变量。

## 3. Python AI Service 配置

当前 Python AI Service 实际从环境变量读取：

- `DASHSCOPE_API_KEY`

示例：

```env
DASHSCOPE_API_KEY=your_dashscope_api_key_here
```

不要写入真实 key。

模型、Milvus、Runbook retrieval 等其他配置当前主要使用 `faultlab-ai-service/app/config.py` 中的代码默认值，包括：

- `dashscope_base_url`
- `llm_enabled`
- `llm_timeout_seconds`
- `llm_default_model`
- `llm_fast_model`
- `llm_reasoning_model`
- `llm_long_context_model`
- `embedding_model`
- `embedding_dimension`
- `embedding_base_url`
- `milvus_host`
- `milvus_port`
- `milvus_collection_name`
- `retrieval_mode`
- `retrieval_top_k`
- `rerank_enabled`

这些配置目前不是通过 `.env.example` 暴露的环境变量。修改前应先确认代码是否已经支持读取对应变量。

## 4. Frontend 配置

本地开发时，Vite dev server 通过 `faultlab-frontend/vite.config.js` 代理接口：

```text
/api -> http://localhost:8080
/ai  -> http://localhost:8000
```

当前版本不依赖线上 `VITE_BACKEND_URL` 或类似构建时变量。

如果未来部署，需要通过 Nginx、反向代理或构建时环境变量处理接口地址；当前文档不声明已经支持这些部署变量。

## 5. Docker Compose 配置

`deploy/docker-compose.yml` 启动本地基础设施：

- MySQL
- Redis
- RabbitMQ
- Milvus etcd
- Milvus MinIO
- Milvus standalone

`deploy/.env.example` 提供本地默认值：

- `MYSQL_DATABASE`
- `MYSQL_USERNAME`
- `MYSQL_PASSWORD`
- `MYSQL_ROOT_PASSWORD`
- `RABBITMQ_USERNAME`
- `RABBITMQ_PASSWORD`
- `MILVUS_MINIO_ACCESS_KEY`
- `MILVUS_MINIO_SECRET_KEY`
- `MILVUS_MINIO_API_PORT`
- `MILVUS_MINIO_CONSOLE_PORT`

这些默认账号密码仅用于本地开发，不建议直接作为生产配置。

## 6. 不应提交的文件

- `faultlab-ai-service/.env`
- `faultlab-ai-service/.venv`
- `faultlab-ai-service/data/runbook_index_state.json`
- `faultlab-ai-service/data/runbook_index_tasks.json`
- `faultlab-ai-service/evaluation/reports/*.md`
- `faultlab-ai-service/evaluation/reports/*.json`
- `faultlab-frontend/node_modules`
- `deploy/.env`，如果包含真实配置

## 7. 本地环境检查清单

- [ ] Docker Desktop / Docker Engine 可用
- [ ] MySQL / Redis / RabbitMQ / Milvus 容器已启动
- [ ] Java Backend 可以访问 MySQL / Redis / RabbitMQ
- [ ] AI Service 可以启动
- [ ] 如果需要 AI Report，已配置模型 API key
- [ ] Frontend 可以访问 `/api` 和 `/ai`
- [ ] `.env` 未被提交
- [ ] README 中没有真实密钥
