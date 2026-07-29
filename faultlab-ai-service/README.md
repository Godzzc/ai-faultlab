# faultlab-ai-service

`faultlab-ai-service` 是 AI FaultLab 的 Python FastAPI AI 诊断服务。

当前服务已经接入阿里云百炼 OpenAI 兼容接口，用于基于 Java 后端传入的 Evidence Package 生成结构化 AI 诊断报告。

## 当前能力

- 接收 Evidence Package
- 构造受证据约束的 LLM Prompt
- 调用阿里云百炼 OpenAI 兼容接口
- 支持基础 ModelRouter
- 解析和校验 LLM 输出 JSON
- 补齐缺失字段并限制 `confidence` 到 `0..1`
- LLM 不可用、超时、空响应、JSON 非法时自动 fallback
- 未配置 `DASHSCOPE_API_KEY` 时自动 fallback，不调用 LLM

## 配置

当前只有 `DASHSCOPE_API_KEY` 需要作为环境变量配置：

```bash
DASHSCOPE_API_KEY=your-bailian-api-key
```

其他 LLM 配置使用 `app/config.py` 中的代码默认值，包括：

- `dashscope_base_url`
- `llm_enabled`
- `llm_timeout_seconds`
- `llm_max_retries`
- `llm_default_model`
- `llm_fast_model`
- `llm_reasoning_model`
- `llm_long_context_model`

模型名称以 `app/config.py` 当前值为准。后续如果需要调整模型，直接修改该文件即可。

## ModelRouter

`ModelRouter` 根据诊断证据复杂度选择模型：

- `ruleResult` 缺失或 `matched=false`：fast model
- Trace 节点数较多：reasoning model
- 规则证据数量较多：reasoning model
- Metrics 数量较多：long context model
- 普通诊断：default model

模型选择结果只记录到服务日志，不修改对外响应结构。

## Install

```bash
pip install -r requirements.txt
```

## Start

```bash
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

## Health Check

```text
GET http://localhost:8000/ai/health
```

Response:

```json
{
  "service": "faultlab-ai-service",
  "status": "UP"
}
```

## Diagnosis API

```text
POST http://localhost:8000/ai/diagnosis/generate
```

请求体是 Java 后端组装的 Evidence Package，包含：

- `experiment`
- `metrics`
- `traceTree`
- `ruleResult`

响应是固定结构的 `DiagnosisResponse`：

```json
{
  "experimentId": "exp_xxx",
  "faultType": "MQ_BACKLOG",
  "faultName": "MQ 消息堆积",
  "confidence": 0.85,
  "summary": "本次实验检测到 MQ 消息堆积风险。",
  "phenomenon": [],
  "evidence": [],
  "rootCauses": [],
  "suggestions": [],
  "runbookReferences": [],
  "fallback": false
}
```

## Fallback

以下情况会自动返回降级报告：

- 未配置 `DASHSCOPE_API_KEY`
- `settings.llm_enabled=False`
- LLM 调用超时或异常
- LLM 返回空内容
- LLM 返回非 JSON
- JSON 结构不符合预期

降级报告会尽量保留 `ruleResult.evidence` 和 `ruleResult.suggestions`，方便前端继续展示。

## Not Included Yet

- Runbook RAG
- LangGraph
- MCP
- 向量库
- Tool Calling

## Test

```bash
python -m pytest
```
