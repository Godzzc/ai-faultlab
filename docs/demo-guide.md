# AI FaultLab Demo Guide

本文档用于演示 AI FaultLab v0.3.0 的完整诊断链路：

```text
故障演练
  -> Metrics / Trace
  -> 规则诊断
  -> Evidence Package
  -> Python AI Service
  -> ModelRouter
  -> 百炼 LLM
  -> JSON 校验 / fallback
  -> Java 落库
  -> Vue Dashboard 展示
```

## 环境准备

需要本地具备：

- Docker / Docker Compose
- Java 17
- Maven
- Python 3.11+
- Node.js / npm
- 阿里云百炼 API Key

只需要配置一个敏感环境变量：

```text
DASHSCOPE_API_KEY
```

其他 LLM 参数使用 `faultlab-ai-service/app/config.py` 的默认值。

## 启动 Docker

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

默认本地账号通常是：

```text
faultlab / faultlab123456
```

## 启动 Java Backend

推荐使用 IDEA 启动：

```text
FaultLabBackendApplication
```

本地环境变量示例：

```text
MYSQL_HOST=localhost;MYSQL_PORT=3306;MYSQL_DATABASE=faultlab;MYSQL_USERNAME=faultlab;MYSQL_PASSWORD=faultlab123456;RABBITMQ_HOST=localhost;RABBITMQ_PORT=5672;RABBITMQ_USERNAME=faultlab;RABBITMQ_PASSWORD=faultlab123456;REDIS_HOST=localhost;REDIS_PORT=6379
```

健康检查：

```text
GET http://localhost:8080/api/health
```

## 启动 Python AI Service

PowerShell 中设置长期环境变量：

```powershell
setx DASHSCOPE_API_KEY "你的真实百炼APIKey"
```

重新打开 PowerShell 后启动服务：

```powershell
cd faultlab-ai-service
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

如果未使用虚拟环境：

```bash
cd faultlab-ai-service
pip install -r requirements.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

健康检查：

```text
GET http://localhost:8000/ai/health
```

## 启动前端

```bash
cd faultlab-frontend
npm install
npm.cmd run dev
```

访问：

```text
http://localhost:5173
```

## MQ_BACKLOG 演示

1. 打开 Dashboard。
2. 选择 `MQ_BACKLOG`。
3. 参数建议：
   - `messageCount=10`
   - `consumerDelayMs=1000`
4. 点击“开始演练”。
5. 查看 Metrics 表格。
6. 查看 Trace 树。
7. 点击“执行规则诊断”。
8. 点击“生成 AI 诊断报告”。

预期结果：

- Metrics 中包含 `publishCount`、`consumeCount`、`consumerDelayMs`、`avgConsumeMs`
- 规则诊断返回 `faultType=MQ_BACKLOG`
- AI Report 展示 summary、evidence、rootCauses、suggestions
- Python AI Service 日志打印 selected model 和 route reason

## THREAD_POOL_SATURATION 演示

1. 选择 `THREAD_POOL_SATURATION`。
2. 参数建议：
   - `taskCount=30`
   - `taskSleepMs=3000`
3. 点击“开始演练”。
4. 点击“执行规则诊断”。
5. 点击“生成 AI 诊断报告”。

预期结果：

- Metrics 中包含 `acceptedTaskCount`、`rejectedTaskCount`、`activeThreadCount`、`queueSize`
- 规则诊断返回 `faultType=THREAD_POOL_SATURATION`
- AI Report 对线程池饱和风险给出结构化总结

## IDEMPOTENCY_CONFLICT 演示

1. 选择 `IDEMPOTENCY_CONFLICT`。
2. 参数建议：
   - `requestCount=30`
   - `duplicateCount=20`
   - `conflictCount=8`
   - `processingDelayMs=1000`
3. 点击“开始演练”。
4. 点击“执行规则诊断”。
5. 点击“生成 AI 诊断报告”。

预期结果：

- Metrics 中包含 `duplicateCount`、`hashMismatchCount`、`redisSetNxFailCount`
- 规则诊断返回 `faultType=IDEMPOTENCY_CONFLICT`
- AI Report 对重复提交、参数冲突或幂等 key 风险给出结构化总结

## 规则诊断

规则诊断由 Java Backend 执行：

```text
POST /api/diagnosis/{experimentId}/rule
```

规则诊断结果会保存到 `diagnosis_report.rule_result_json`，也是 AI Evidence Package 的关键输入。

## AI 诊断

AI 诊断由 Java Backend 触发：

```text
POST /api/diagnosis/{experimentId}/ai/generate
```

Java Backend 会：

1. 查询实验信息
2. 查询 Metrics
3. 查询 Trace 树
4. 查询或生成规则诊断
5. 组装 Evidence Package
6. 调用 Python AI Service
7. 保存 AI 报告到 `diagnosis_report.ai_report_json`

如果 Python AI Service 未启动、百炼调用失败或 JSON 非法，后端会保存并返回 fallback 报告。

## Trace 树展示

Trace 树由前端调用：

```text
GET /api/traces/{traceId}
```

Dashboard 会展示 span 的 operation、component、duration、status 和时间信息。

## 常见问题排查

### RabbitMQ Connection refused

检查 RabbitMQ 容器是否启动：

```bash
cd deploy
docker compose ps
docker compose logs -f rabbitmq
```

确认后端连接的是 `localhost:5672`。

### RabbitMQ ACCESS_REFUSED

检查 Java Backend 环境变量：

```text
RABBITMQ_USERNAME=faultlab
RABBITMQ_PASSWORD=faultlab123456
```

如果 `deploy/.env` 修改过账号密码，IDEA 中的后端环境变量也要同步。

### AI Service fallback=true

常见原因：

- 未配置 `DASHSCOPE_API_KEY`
- `DASHSCOPE_API_KEY` 配置后没有重新打开 PowerShell
- 百炼接口不可用或超时
- LLM 返回内容不是合法 JSON
- `settings.llm_enabled=False`

先检查：

```text
GET http://localhost:8000/ai/health
```

再查看 uvicorn 日志中的 fallback reason。

### Java 调 Python 超时

检查后端配置：

```yaml
faultlab:
  ai-service:
    read-timeout-ms: ${AI_SERVICE_READ_TIMEOUT_MS:120000}
```

如果本地模型响应较慢，可以在启动 Java Backend 时显式设置：

```text
AI_SERVICE_READ_TIMEOUT_MS=120000
```

### DASHSCOPE_API_KEY 未配置

PowerShell 中设置：

```powershell
setx DASHSCOPE_API_KEY "你的真实百炼APIKey"
```

重新打开 PowerShell，再启动 AI Service。

临时当前窗口设置：

```powershell
$env:DASHSCOPE_API_KEY="你的真实百炼APIKey"
```

### PowerShell 中文乱码

可以在当前窗口执行：

```powershell
chcp 65001
```

或者优先通过浏览器 Dashboard 查看中文内容。

### 前端展示旧 AI 报告

可能原因：

- 当前 `experimentId` 没切换
- 只刷新了页面，没有重新生成 AI 报告
- 浏览器仍显示上一次查询结果

处理方式：

1. 确认当前 `experimentId`。
2. 点击“刷新实验数据”。
3. 再点击“生成 AI 诊断报告”。
4. 调用 `GET /api/diagnosis/{experimentId}` 确认 `aiReportJson` 已更新。
