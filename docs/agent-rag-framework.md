# AI FaultLab Agent and RAG Framework

本文档记录 AI FaultLab 的 AI 诊断链路设计。当前文档对应 `v0.3.0: LLM Diagnosis Demo Version`。

## 1. 设计目标

AI FaultLab 的 AI 能力不是通用聊天机器人，也不是让模型凭空判断故障原因。

目标是构建一个基于证据的诊断链路：

```text
故障演练
  -> Trace / Metrics / Rule Diagnosis
  -> Evidence Package
  -> ModelRouter
  -> LLM Diagnosis
  -> JSON Validate
  -> Fallback
  -> Diagnosis Report
```

核心原则：

- Evidence First：先收集 Trace、Metrics、Rule Diagnosis，再交给 AI。
- Rule Before LLM：规则诊断是确定性兜底，不能被模型替代。
- JSON Before Display：AI 输出必须校验为固定 JSON Schema。
- Fallback Before Failure：AI 失败时返回可展示的降级报告。
- RAG Before Production：进入生产级知识增强前，需要先接入 Runbook 检索。

## 2. 当前阶段定位

当前阶段是：

```text
固定 Workflow + 百炼 LLM 诊断 Demo
```

当前不是：

```text
自由 ReAct Agent
多 Agent 系统
Runbook RAG
Tool Calling
MCP Server
生产级诊断平台
```

## 3. 当前已完成

### Evidence Package

Java Backend 已负责组装 Evidence Package，包含：

- `experiment`
- `metrics`
- `traceTree`
- `ruleResult`

Python AI Service 基于 Evidence Package 构造 prompt，要求模型只能使用已有证据，不编造不存在的指标、Trace 节点或 Runbook 引用。

### LLM Diagnosis

Python AI Service 已接入阿里云百炼 OpenAI 兼容接口。

当前流程：

```text
DiagnosisRequest
  -> build_trace_summary
  -> build_prompt
  -> ModelRouter.select_model
  -> LlmClient.generate
  -> parse_and_validate_json
  -> fallback_if_needed
```

### ModelRouter

当前已有基础模型路由：

- `ruleResult` 缺失或未命中：fast model
- Trace 节点数较多：reasoning model
- 规则证据数量较多：reasoning model
- Metrics 数量较多：long context model
- 其他情况：default model

模型选择只记录到 Python 服务日志，不改变对外 API 响应结构。

### JSON Validate

AI 输出会经过 JSON 解析与校验：

- 只接受 JSON object
- 兼容 ```json code block
- 缺失字段使用安全默认值
- `confidence` 限制在 `0..1`
- 列表字段必须是数组，否则置为空数组
- 成功解析时 `fallback=false`

### Fallback

以下情况会进入 fallback：

- 未配置 `DASHSCOPE_API_KEY`
- `settings.llm_enabled=False`
- LLM 调用异常或超时
- LLM 返回空内容
- LLM 返回非法 JSON
- workflow 内部异常

fallback 报告优先基于 `ruleResult` 生成，保留 `evidence` 和 `suggestions`，保证前端仍可展示诊断结果。

## 4. 当前架构

```text
Frontend Dashboard
  |
  v
Java Backend
  |-- Experiment Query
  |-- Metrics Query
  |-- Trace Query
  |-- Rule Diagnosis
  |-- Evidence Package Builder
  |-- AI Diagnosis Client
  |-- Diagnosis Report Persistence
  |
  v
Python AI Service
  |-- Diagnosis Workflow
  |-- Prompt Builder
  |-- ModelRouter
  |-- LlmClient
  |-- JSON Parser / Validator
  |-- Fallback Builder
  |
  v
Alibaba Cloud Bailian LLM
```

## 5. DiagnosisResponse Schema

AI 输出必须符合固定结构：

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

禁止输出：

- 无证据结论
- 编造指标
- 编造 Trace 节点
- 编造 Runbook 引用
- 与 `ruleResult` 明显冲突的判断
- 非 JSON 结构

## 6. 下一阶段：Runbook RAG Basic

下一阶段重点不是扩展 Agent 自主性，而是接入可控的 Runbook RAG。

建议目标：

```text
Evidence Package
  -> Query Build
  -> Runbook Retrieval
  -> Prompt with Runbook Chunks
  -> LLM Diagnosis
  -> JSON Validate
  -> Fallback
```

## 7. Retrieval Abstraction

建议先定义检索抽象，而不是直接绑定 Milvus：

```python
class RunbookRetriever:
    def retrieve(self, query: RunbookQuery) -> list[RunbookChunk]:
        ...
```

第一版可以使用本地 Markdown + 关键词匹配。

后续再替换或扩展为：

- BM25
- Milvus Vector Retrieval
- Hybrid Retrieval
- Rerank

## 8. Runbook 文档结构建议

Runbook 建议按故障类型组织：

```text
faultlab-runbook/
  mq-backlog.md
  thread-pool-saturation.md
  idempotency-conflict.md
```

每份 Runbook 建议包含：

- 故障现象
- 判断指标
- 常见原因
- 排查步骤
- 修复建议
- 预防措施
- 面试解释

## 9. 文档切分策略

Runbook 不建议一开始按固定长度粗切。

优先按结构切分：

- 按故障类型
- 按标题层级
- 按排查步骤
- 按修复建议

chunk metadata 示例：

```json
{
  "docId": "mq-backlog",
  "faultType": "MQ_BACKLOG",
  "section": "排查步骤",
  "title": "消费者处理过慢",
  "chunkIndex": 3
}
```

## 10. 后续路线

### v0.3.0 当前版本

```text
故障演练
Trace
Metrics
规则诊断
Evidence Package
ModelRouter
百炼 LLM
JSON Validate
Fallback
Frontend AI Report
```

### 下一阶段

```text
Runbook RAG Basic
Retrieval Abstraction
Markdown Runbook Chunking
Keyword Retrieval
Prompt with Retrieved Chunks
```

### 后续增强

```text
Milvus Vector Retrieval
Hybrid Retrieval / Rerank
Tool Calling
LangGraph
MCP Server
```

这些能力当前尚未完成，不能在对外说明中写成已支持。
