# Screenshots Guide

## 1. 截图用途

这些截图用于 GitHub README 和文档展示，帮助新用户快速理解 AI FaultLab 的页面结构、故障演练流程和诊断闭环。

当前项目暂不提供公网在线 Demo，截图均来自本地完整运行环境。

## 2. 本地启动要求

截图前需要本地启动：

1. 基础设施
2. Java Backend
3. Python AI Service
4. Frontend

具体启动方式参考：

- docs/local-dev.md
- docs/demo-guide.md

## 3. 推荐截图清单

建议采集以下截图：

### Dashboard 首页

文件名：

- `dashboard-hero.png`

内容：

- Hero 区域
- 能力标签
- 场景分组入口

### 场景分组与参数表单

文件名：

- `scenario-groups-and-params.png`

内容：

- 12 个故障场景按领域分组
- 当前选中场景
- 参数配置面板

### 实验 Overview

文件名：

- `experiment-overview.png`

内容：

- experimentId
- scenarioCode
- status
- traceId
- ruleDiagnosis 简要信息

### Metrics Summary

文件名：

- `metrics-summary.png`

内容：

- 场景关键指标摘要
- 完整 Metrics 表格入口

### Trace View

文件名：

- `trace-view.png`

内容：

- root span
- child spans
- durationMs
- status
- error span 样式

### Rule Diagnosis

文件名：

- `rule-diagnosis.png`

内容：

- faultType
- faultName
- reason
- evidence
- suggestions

### AI Report

文件名：

- `ai-report.png`

内容：

- AI 诊断报告生成结果
- 报告内容展示
- 复制报告按钮

### RAG Console

文件名：

- `rag-console.png`

内容：

- RAG Evaluation
- Retrieval Debug
- Runbook Management

## 4. 推荐演示场景

截图时优先使用以下场景：

- `CACHE_BREAKDOWN`
- `DB_CONNECTION_POOL_EXHAUSTION`
- `DOWNSTREAM_TIMEOUT`
- `RETRY_STORM`

这些场景更适合展示缓存、数据库、下游调用和重试放大的排查链路。

## 5. 截图保存位置

截图统一保存到：

```text
docs/assets/screenshots/
```

推荐文件名：

```text
dashboard-hero.png
scenario-groups-and-params.png
experiment-overview.png
metrics-summary.png
trace-view.png
rule-diagnosis.png
ai-report.png
rag-console.png
```

## 6. README 引用方式

当截图文件已经真实存在后，可以在 README 中引用：

```markdown
![Dashboard Hero](docs/assets/screenshots/dashboard-hero.png)
```

注意：

在图片文件加入仓库之前，不要在 README 中启用不存在图片的引用。

## 7. 截图采集建议

- 使用浏览器正常缩放比例，例如 100% 或 90%
- 尽量截完整页面模块，不要只截局部按钮
- 不要截包含真实密钥、私有 IP、个人信息的内容
- 如果 AI Report 使用真实模型生成，注意报告内容不要包含敏感信息
- 截图文件尽量压缩到合理大小，避免仓库过大
