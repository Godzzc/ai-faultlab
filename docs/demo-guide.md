# AI FaultLab Demo Guide

## 1. 项目一句话介绍

AI FaultLab 是一个后端故障演练与 AI 诊断平台，用于模拟典型 Java 后端故障，并通过 Metrics、Trace、规则诊断、Runbook RAG 和 AI 报告形成排查闭环。

它是学习型 / Demo 工程，重点展示故障排查链路的工程组织方式，不是生产级压测平台或完整 AIOps 产品。

## 2. 当前故障场景覆盖

基础后端稳定性：

- `MQ_BACKLOG`
- `THREAD_POOL_SATURATION`
- `IDEMPOTENCY_CONFLICT`

缓存故障：

- `CACHE_PENETRATION`
- `CACHE_BREAKDOWN`
- `CACHE_AVALANCHE`

数据库瓶颈：

- `DB_SLOW_QUERY`
- `DB_LOCK_CONTENTION`
- `DB_CONNECTION_POOL_EXHAUSTION`

下游调用韧性：

- `DOWNSTREAM_TIMEOUT`
- `RETRY_STORM`
- `CIRCUIT_BREAKER_OPEN`

## 3. 推荐演示路径

### Demo 1：缓存击穿

场景：`CACHE_BREAKDOWN`

建议参数：

- `requestCount`: `100`
- `hotKey`: `hot:item:1`
- `concurrency`: `20`
- `rebuildDelayMs`: `100`
- `enableMutex`: `false`
- `enableLogicalExpire`: `false`

讲解重点：

- 热点 key 失效后大量请求同时打到后端。
- 观察 `cache.hot.key.miss.count`。
- 观察 `cache.rebuild.count`。
- 观察 `cache.concurrent.rebuild.count`。
- 对比 `enableMutex=true` 后的变化。

### Demo 2：数据库连接池耗尽

场景：`DB_CONNECTION_POOL_EXHAUSTION`

建议参数：

- `requestCount`: `100`
- `concurrency`: `30`
- `maxPoolSize`: `10`
- `queryDelayMs`: `200`
- `connectionAcquireTimeoutMs`: `50`
- `enableFastRelease`: `false`

讲解重点：

- 慢查询或长事务占用连接。
- 连接池 active 接近 max。
- 新请求获取连接超时。
- 观察 `db.connection.acquire.timeout.count`。
- 观察 `db.connection.acquire.avg.ms`。

### Demo 3：下游接口超时

场景：`DOWNSTREAM_TIMEOUT`

建议参数：

- `requestCount`: `100`
- `concurrency`: `20`
- `downstreamDelayMs`: `300`
- `timeoutMs`: `100`
- `timeoutRatio`: `0.8`
- `enableFallback`: `false`
- `fallbackDelayMs`: `10`

讲解重点：

- 下游响应时间超过 `timeoutMs`。
- 当前服务 `api.error.count` 上升。
- 开启 fallback 后错误减少。
- 观察 `downstream.timeout.count`。
- 观察 `downstream.fallback.count`。

### Demo 4：重试风暴

场景：`RETRY_STORM`

建议参数：

- `requestCount`: `100`
- `concurrency`: `20`
- `failureRatio`: `0.7`
- `maxRetries`: `3`
- `retryBackoffMs`: `20`
- `enableRetryLimit`: `false`
- `enableJitter`: `false`

讲解重点：

- 下游失败后上游重试放大流量。
- total downstream call 明显大于 initial request。
- 观察 `downstream.retry.amplification.factor`。
- 说明为什么需要 retry budget、backoff、jitter 和熔断。

## 4. 本地启动顺序

### 1. 启动基础设施

WSL：

```bash
cd /mnt/d/JavaProjects/ai-faultlab/deploy
docker compose up -d
```

也可以在项目根目录的 `deploy` 目录下用 Docker Compose 启动。基础设施包括 MySQL、Redis、RabbitMQ，以及 RAG 索引需要的 Milvus。

### 2. 启动 Java 后端

PowerShell：

```powershell
cd D:\JavaProjects\ai-faultlab\faultlab-backend
mvn.cmd spring-boot:run
```

后端健康检查：

```text
GET http://localhost:8080/api/health
```

### 3. 启动 AI Service

PowerShell：

```powershell
cd D:\JavaProjects\ai-faultlab\faultlab-ai-service
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

AI Service 健康检查：

```text
GET http://localhost:8000/ai/health
```

如果要调用真实模型，需要先配置 `DASHSCOPE_API_KEY`。未配置时，诊断链路仍可通过 fallback 报告演示基本流程。

### 4. 启动前端

PowerShell：

```powershell
cd D:\JavaProjects\ai-faultlab\faultlab-frontend
npm.cmd run dev
```

### 5. 打开页面

```text
http://localhost:5173
```

## 5. 演示时怎么讲

我这个项目不是简单做故障触发，而是把故障演练、指标采集、Trace、规则诊断、Runbook 检索和 AI 报告串成一条链路。用户选择一个故障场景后，系统会生成对应的实验记录和指标，再通过规则诊断判断故障类型，最后把结构化证据交给 AI 服务生成诊断报告。

演示页面可以按这个顺序讲：

1. 先选一个场景并启动实验。
2. 看 Overview，确认实验 ID、场景、状态和 traceId。
3. 看 Metrics Summary，说明这个故障最关键的指标。
4. 看 Trace，定位故障发生在缓存、DB、下游调用或重试阶段。
5. 执行规则诊断，解释 reason、evidence 和 suggestions。
6. 生成 AI Report，说明 AI 报告不是凭空回答，而是基于结构化证据和 Runbook RAG。

## 6. 已知限制

- 当前故障多为 deterministic simulation，不是真实生产压测。
- BM25 使用本地 Okapi BM25 实现，并叠加轻量领域 boost。
- lightweight rerank 不是真实 rerank 模型。
- Milvus 检索效果依赖本地索引是否已重建。
- AI 报告依赖 Python AI Service 和模型配置。
- 当前未做登录鉴权。
- 当前未做生产级部署。
