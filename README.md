# AI FaultLab

AI FaultLab 是一个面向 Java 后端典型故障的故障演练与智能诊断平台。

它不是简单的 AI 外壳，而是围绕故障场景模拟、指标采集、Trace 链路追踪、规则诊断，以及后续 AI / Runbook RAG 扩展，形成一个可以演示的诊断闭环。

## 当前已支持能力

### MQ_BACKLOG：MQ 消息堆积

- 通过 RabbitMQ 发送消息
- 消费端延迟消费，模拟慢消费导致的堆积
- 记录 `publishCount`、`consumeCount`、`avgConsumeMs`、`consumerDelayMs` 等指标
- 支持规则诊断，输出 `evidence` 和 `suggestions`

### THREAD_POOL_SATURATION：线程池饱和

- 使用专用线程池，不影响 Spring Boot 其他异步任务
- 异步提交长任务，模拟活跃线程打满、队列堆积、任务拒绝
- 记录 `activeThreadCount`、`queueSize`、`rejectedTaskCount`、`avgTaskDurationMs` 等指标
- 支持规则诊断

### IDEMPOTENCY_CONFLICT：幂等冲突

- 使用 Redis `SETNX` 模拟幂等检查快路径
- 基于 `idempotencyKey` 和 `requestHash` 判断重复提交和参数冲突
- 记录 `duplicateCount`、`hashMismatchCount`、`redisSetNxFailCount`、`redisErrorCount` 等指标
- 支持规则诊断

## 核心流程

```text
用户选择故障场景
  -> 启动故障演练
  -> 写入实验记录
  -> 执行故障模拟
  -> 采集指标
  -> 写入 Trace
  -> 执行规则诊断
  -> 生成结构化诊断结果
  -> 前端 Dashboard 展示
```

## 技术栈

后端：

- Java 17
- Spring Boot 3.x
- MyBatis-Plus
- MySQL
- Redis
- RabbitMQ
- JUnit5 / Mockito

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
├── faultlab-backend       # Spring Boot 后端主服务
├── faultlab-frontend      # Vue3 + Vite Dashboard
├── faultlab-ai-service    # AI 诊断服务目录，当前仍是后续规划重点
├── faultlab-runbook       # Runbook 文档目录
├── faultlab-trace-sdk     # Trace SDK 目录
├── deploy                 # Docker Compose 本地环境
├── docs                   # 项目文档
├── scripts                # 辅助脚本
└── README.md
```

## 本地启动方式

### 1. 启动 Docker Compose

建议在 WSL 中执行：

```bash
cd /mnt/d/JavaProjects/ai-faultlab/deploy
docker compose up -d
docker compose ps
```

正常应看到以下容器启动：

- `faultlab-mysql`
- `faultlab-redis`
- `faultlab-rabbitmq`

RabbitMQ 管理台：

```text
http://localhost:15672
```

### 2. 启动后端

使用 IDEA 启动：

```text
FaultLabBackendApplication
```

建议配置 Environment variables：

```text
MYSQL_HOST=localhost;MYSQL_PORT=3306;MYSQL_DATABASE=faultlab;MYSQL_USERNAME=faultlab;MYSQL_PASSWORD=faultlab123456;RABBITMQ_HOST=localhost;RABBITMQ_PORT=5672;RABBITMQ_USERNAME=faultlab;RABBITMQ_PASSWORD=faultlab123456;REDIS_HOST=localhost;REDIS_PORT=6379
```

健康检查：

```text
GET http://localhost:8080/api/health
```

### 3. 启动前端

```bash
cd faultlab-frontend
npm install
npm run dev
```

访问：

```text
http://localhost:5173
```

如果 PowerShell 执行策略拦截 `npm`，可以使用：

```bash
npm.cmd run build
```

## 常用接口

- `POST /api/experiments/start`：启动故障演练
- `GET /api/experiments/{experimentId}`：查询实验详情
- `GET /api/experiments/{experimentId}/metrics`：查询实验指标
- `POST /api/diagnosis/{experimentId}/rule`：执行规则诊断
- `GET /api/diagnosis/{experimentId}`：查询诊断报告
- `GET /api/traces/{traceId}`：查询 Trace 树

## 演示步骤

1. 打开前端 Dashboard：`http://localhost:5173`
2. 选择 `MQ_BACKLOG`
3. 使用默认参数，或设置 `messageCount=10`、`consumerDelayMs=1000`
4. 点击“开始演练”
5. 查看实验信息和指标列表
6. 点击“执行规则诊断”
7. 查看 `faultType`、`confidence`、`evidence`、`suggestions`
8. 切换 `THREAD_POOL_SATURATION` 和 `IDEMPOTENCY_CONFLICT` 重复演示
9. 说明 Trace 树当前已接入接口，但异步场景下 `TraceContext` 跨线程传播仍是后续 TODO

## 当前项目亮点

- 自研轻量级 Trace，基于 `traceId` / `spanId` / `parentSpanId` 记录关键调用节点
- 基于 `@TraceSpan` + AOP 自动埋点，降低业务侵入
- 支持三个 Java 后端典型故障场景演练
- 基于指标的规则诊断，避免直接让 AI 凭空判断
- Docker Compose 编排 MySQL、Redis、RabbitMQ，可本地一键启动
- Vue Dashboard 支持故障演练闭环可视化展示

## 已知限制 / TODO

- 当前 AI 诊断和 Runbook RAG 还未实现
- 当前 `TraceContext` 跨线程 / MQ 消息传播还未完善
- 当前前端是 MVP 单页，没有实验列表分页、自动轮询和图表
- 当前规则诊断是第一版 Java 规则判断，后续可接入 AI 诊断报告生成

## License

MIT
