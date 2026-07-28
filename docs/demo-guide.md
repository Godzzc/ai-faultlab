# AI FaultLab Demo Guide

本文档用于面试或技术分享时演示 AI FaultLab 的故障演练闭环。

## 演示目标

通过一个前端 Dashboard 展示：

- 故障演练：选择典型 Java 后端故障并触发模拟
- 指标采集：观察实验过程中写入的关键指标
- 规则诊断：基于指标输出结构化诊断结果
- 前端可视化：展示实验信息、指标、诊断证据、建议和 Trace 树

## 演示前准备

确认以下组件已经就绪：

- Docker Compose 已启动
- 后端 `FaultLabBackendApplication` 已启动
- 前端 Vite dev server 已启动
- RabbitMQ 管理台可访问：`http://localhost:15672`
- MySQL 表已通过 `schema.sql` 初始化

基础检查：

```bash
cd deploy
docker compose ps
```

健康检查：

```text
GET http://localhost:8080/api/health
```

前端页面：

```text
http://localhost:5173
```

## 演示路径一：MQ 消息堆积

页面操作：

1. 选择 `MQ_BACKLOG`
2. 参数建议：
   - `messageCount=10`
   - `consumerDelayMs=1000` 或 `5000`
3. 点击“开始演练”
4. 点击“执行规则诊断”

预期结果：

- 指标中出现 `publishCount`、`consumeCount`、`consumerDelayMs`、`avgConsumeMs` 等
- 诊断结果 `faultType=MQ_BACKLOG`
- `confidence` 根据积压和慢消费证据返回
- `suggestions` 包含增加消费者并发、排查慢消费等建议

## 演示路径二：线程池饱和

页面操作：

1. 选择 `THREAD_POOL_SATURATION`
2. 参数建议：
   - `taskCount=30`
   - `taskSleepMs=3000`
3. 点击“开始演练”
4. 点击“执行规则诊断”

预期结果：

- 指标中出现 `acceptedTaskCount`、`rejectedTaskCount`、`activeThreadCount`、`queueSize`
- 诊断结果 `faultType=THREAD_POOL_SATURATION`
- `suggestions` 包含调整线程池参数、限流、拆分长任务等建议

## 演示路径三：幂等冲突

页面操作：

1. 选择 `IDEMPOTENCY_CONFLICT`
2. 参数建议：
   - `requestCount=30`
   - `duplicateCount=20`
   - `conflictCount=8`
   - `processingDelayMs=1000`
3. 点击“开始演练”
4. 点击“执行规则诊断”

预期结果：

- 指标中出现 `duplicateCount`、`hashMismatchCount`、`redisSetNxFailCount`
- 诊断结果 `faultType=IDEMPOTENCY_CONFLICT`
- `suggestions` 包含 `Idempotency-Key`、`requestHash`、Redis 快路径、DB 唯一索引兜底等建议

## 常见问题排查

### RabbitMQ Connection refused

检查 Docker Compose 中 RabbitMQ 是否启动，以及 5672 端口是否映射：

```bash
cd deploy
docker compose ps
```

### RabbitMQ ACCESS_REFUSED

检查 IDEA 后端启动配置中的环境变量：

```text
RABBITMQ_USERNAME=faultlab
RABBITMQ_PASSWORD=faultlab123456
```

如果 `deploy/.env` 中使用了不同账号密码，后端环境变量也需要保持一致。

### 前端请求失败

检查：

- 后端是否已启动在 `http://localhost:8080`
- Vite 代理是否配置 `/api -> http://localhost:8080`
- 前端是否通过 `npm run dev` 启动

### Trace 树为空

当前异步任务 `TraceContext` 尚未跨线程传播，部分 Span 可能写入独立 `traceId`。这是当前 V1 已知限制，后续可以通过 `TaskDecorator` 或包装 `Runnable` 传播上下文。

### 修改 schema.sql 后表结构没变化

MySQL 初始化脚本只会在数据卷首次创建时执行。需要清理 volume 后重新启动：

```bash
cd deploy
docker compose down -v
docker compose up -d
```

## 面试讲解建议

这个项目不是简单的 CRUD 或 AI 调接口，而是围绕 Java 后端典型故障做了一个演练和诊断闭环。用户可以在前端选择故障场景，后端触发真实中间件或线程池行为，采集指标和 Trace，再通过规则诊断生成 evidence 和 suggestions。后续 AI 诊断会基于这些结构化证据和 Runbook，而不是直接让大模型凭空生成结论。
