

# AI FaultLab Agent 与 RAG 技术框架

## 1. 设计目标

AI FaultLab 后续的 AI 能力不是做一个自由聊天机器人，而是做一个基于证据的诊断 Agent。

核心目标：


故障演练
  -> Trace / Metrics / Rule Diagnosis
  -> Evidence Package
  -> Runbook RAG
  -> AI Diagnosis Report


AI 层的职责是基于已有证据生成诊断报告，而不是凭空判断故障原因。

---

## 2. 基本原则


Evidence First
Rule Before LLM
RAG Before Generation
Tool Before Guess
Fallback Before Failure


解释：

* Evidence First：先采集 Trace、指标、规则结果，再交给 AI。
* Rule Before LLM：规则诊断是确定性兜底，不能被大模型替代。
* RAG Before Generation：报告生成前先检索 Runbook。
* Tool Before Guess：能查接口就不要让模型猜。
* Fallback Before Failure：AI 失败时降级为规则诊断报告。

---

## 3. Agent 定位

本项目中的 Agent 定位为：

```text
诊断型 Agent
```

不是：

```text
通用聊天 Agent
完全自主规划 Agent
多 Agent 炫技系统
```

它的输入、工具和输出都应该受控。

主要职责：

```text
接收 Evidence Package
  -> 检索 Runbook
  -> 必要时调用工具补充证据
  -> 生成结构化诊断报告
  -> 失败时降级
```

---

## 4. 推荐架构

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
  |
  v
Python AI Service
  |-- Diagnosis Workflow
  |-- Runbook Retriever
  |-- Prompt Builder
  |-- LLM Client
  |-- JSON Validator
  |-- Fallback Builder
```

职责划分：

* Java Backend：负责业务事实、实验数据、指标、Trace、规则结果。
* Python AI Service：负责 AI 编排、RAG、Prompt、模型调用和结果校验。
* LLM：只负责基于证据进行总结、归因和表达。
* Rule Diagnosis：作为确定性诊断和 AI 失败兜底。

---

## 5. Agent 范式选择

当前阶段采用：

```text
固定 Workflow + 局部 AI 生成
```

不直接上自由 ReAct Agent。

第一版流程：

```text
build_evidence
  -> retrieve_runbook
  -> build_prompt
  -> call_llm
  -> validate_json
  -> fallback_if_needed
```

原因：

* 流程清晰
* 容易测试
* 成本可控
* 方便降级
* 不容易幻觉

---

## 6. LangGraph 演进策略

当前阶段暂不直接引入 LangGraph。

原因是当前 AI 诊断链路仍然比较固定，手写 Workflow 更轻量：

```text
Evidence Package
  -> Runbook Retrieval
  -> Prompt Build
  -> LLM Call
  -> JSON Validate
  -> Fallback
```

但代码结构要按 LangGraph 思维设计，方便后续迁移。

要求：

* 定义统一 `DiagnosisState`
* 每个步骤封装为独立 node-like function
* 节点之间只通过 State 传递数据
* 外部调用封装为 Client / Tool
* 输出固定 JSON Schema
* fallback 独立成节点

后续如果出现以下需求，再迁移 LangGraph：

* 多轮工具调用
* 动态分支
* JSON 校验失败后重试
* 低置信度复查
* 人工审核
* checkpoint 恢复
* 多 Agent 协作

---

## 7. DiagnosisState 设计

```python
class DiagnosisState(BaseModel):
    experiment_id: str
    scenario_code: str | None = None

    experiment: dict | None = None
    metrics: list[dict] = []
    trace_tree: dict | None = None
    trace_summary: dict | None = None
    rule_result: dict | None = None

    runbook_queries: list[str] = []
    runbook_chunks: list[dict] = []

    prompt: str | None = None
    llm_raw_output: str | None = None
    diagnosis_report: dict | None = None

    error_code: str | None = None
    error_message: str | None = None
    fallback: bool = False
```

---

## 8. Evidence Package 设计

AI 服务的输入必须是结构化证据包。

示例：

```json
{
  "experiment": {
    "experimentId": "exp_xxx",
    "scenarioCode": "MQ_BACKLOG",
    "status": "RUNNING",
    "traceId": "trace_xxx"
  },
  "metrics": [
    {
      "metricName": "publishCount",
      "metricValue": "10",
      "component": "RabbitMQ"
    },
    {
      "metricName": "consumeCount",
      "metricValue": "1",
      "component": "RabbitMQ"
    }
  ],
  "traceSummary": {
    "slowSpans": [
      {
        "operationName": "mq.consume.order",
        "durationMs": 1027
      }
    ],
    "errorSpans": []
  },
  "ruleResult": {
    "faultType": "MQ_BACKLOG",
    "confidence": 0.85,
    "matched": true,
    "reason": "生产消息数大于消费消息数，且消费耗时较高。"
  }
}
```

---

## 9. Tool Calling 设计

第一版不让模型自由调用大量工具。

后续可开放只读工具：

```text
getExperimentDetail(experimentId)
getMetrics(experimentId)
getTraceTree(traceId)
getRuleDiagnosis(experimentId)
searchRunbook(faultType, keywords)
```

工具设计要求：

* 输入参数固定
* 输出结构化
* 有超时控制
* 失败可降级
* 不暴露敏感配置
* 第一阶段只做只读工具

---

## 10. MCP 定位

MCP 不在第一阶段引入。

当前路线：

```text
V1：HTTP API
V2：Python 内部 Tool Function
V3：MCP Server
```

后续可以把 FaultLab 能力封装成 MCP Tools：

```text
faultlab.getExperiment
faultlab.getMetrics
faultlab.getTraceTree
faultlab.searchRunbook
faultlab.generateDiagnosis
```

MCP 适合在工具能力稳定后，用来对外提供标准化工具接口。

---

## 11. RAG 设计

RAG 在本项目中用于 Runbook 检索，不做泛化知识库问答。

Runbook 建议目录：

```text
faultlab-runbook/
  mq-backlog.md
  thread-pool-saturation.md
  idempotency-conflict.md
```

每份 Runbook 结构：

```text
故障现象
判断指标
常见原因
排查步骤
修复建议
预防措施
面试解释
```

---

## 12. 文档切割策略

Runbook 不按固定长度粗暴切割，优先按结构切：

```text
按故障类型切
按标题层级切
按排查步骤切
按修复建议切
```

每个 chunk 保留 metadata：

```json
{
  "docId": "mq-backlog",
  "faultType": "MQ_BACKLOG",
  "section": "排查步骤",
  "title": "消费者处理过慢",
  "chunkIndex": 3
}
```

切割原则：

* chunk 能独立表达一个诊断点
* 不跨多个故障主题
* 保留标题和 faultType
* 保留 section 信息

---

## 13. Query 优化

不要直接用用户原始问题检索。

Query 来源：

```text
faultType
scenarioCode
ruleResult.reason
metrics
slowSpans
errorSpans
```

示例：

```text
MQ_BACKLOG
publishCount consumeCount avgConsumeMs
RabbitMQ slow consumer backlog
```

Query 优化方式：

* 基于 faultType 扩展关键词
* 基于指标补充诊断词
* 基于 Trace slowSpan 补充调用点
* 多 query 检索后融合

---

## 14. 多路召回

推荐召回链路：

```text
faultType 过滤
  -> BM25 关键词召回
  -> 向量召回
  -> Query Rewrite 召回
  -> 合并去重
  -> RRF 融合
  -> Rerank
```

第一版可以先做：

```text
faultType 过滤 + 关键词匹配
```

后续再增加：

```text
Embedding
BM25
RRF
Rerank
```

---

## 15. Rerank 策略

第一版可以规则打分：

```text
faultType 匹配：+50
命中指标名：+20
section = 排查步骤：+10
section = 修复建议：+10
包含具体操作建议：+10
```

后续再考虑：

* Cross-Encoder Rerank
* LLM Rerank
* 用户反馈优化排序

---

## 16. AI 输出 Schema

AI 输出必须结构化。

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

* 无证据结论
* 编造指标
* 与 ruleResult 冲突的判断
* 非 JSON 结构

---

## 17. 降级策略

AI 是增强能力，不是主链路依赖。

需要降级的情况：

```text
AI Service 超时
LLM 调用失败
JSON 格式不合法
Runbook 检索为空
模型输出与 evidence 冲突
```

降级结果：

```text
使用 ruleResult 生成 fallback report
fallback = true
保留 error_code / error_message
前端仍可展示诊断结果
```

---

## 18. 可观测性

AI 诊断链路也需要 Trace。

建议 Span：

```text
ai.generate.report
evidence.build
runbook.retrieve
prompt.build
llm.call
json.validate
fallback.build
```

建议指标：

```text
aiRequestCount
aiSuccessCount
aiFailureCount
aiFallbackCount
llmLatencyMs
retrievalLatencyMs
retrievedDocCount
jsonValidateFailCount
```

---

## 19. 版本规划

### v0.1

```text
故障演练
Trace
指标采集
规则诊断
前端 Dashboard
```

### v0.2

```text
Python AI Service
Evidence Package
Java AI Client
AI 诊断结果落库
前端展示 AI 报告
```

### v0.3

```text
Runbook RAG
文档切割
关键词召回
向量召回
Rerank
```

### v0.4

```text
Tool Calling Agent
只读工具
按需补充证据
```

### v0.5

```text
MCP Server
标准化暴露 FaultLab 工具能力
```

---

## 20. 当前结论

当前阶段优先实现：

```text
feature/ai-service-skeleton
```

目标是跑通：

```text
Java Evidence Package
  -> Python AI Service
  -> Structured AI Report
  -> Java 落库
  -> Frontend 展示
```

暂不直接引入复杂 Agent 框架、向量库和 MCP。

先保证 AI 诊断闭环真实、可测、可降级，再逐步增强 RAG、Tool Calling 和 MCP。

```
```
