# AI FaultLab RAG Interview Guide

本文档面向面试口述和项目复盘。表述应真实、克制，不把当前 v0.5 demo 夸大成生产级平台。

## 1. 30 秒版本

我在 AI FaultLab 中实现了一个面向故障诊断的 RAG 链路。离线阶段会把 Runbook Markdown 按 section 切分，计算 hash 做索引治理，调用百炼 embedding 写入 Milvus。在线阶段会根据 Evidence Package 同时做 Milvus 向量召回和 BM25-like 关键词召回，再用 RRF 融合和轻量 rerank 选出 topK Runbook chunk，注入 Prompt 让 LLM 生成诊断报告，同时校验 runbookReferences。最后还补了 Hit@K、Recall@K、MRR 的检索评测和 Markdown 报告。

## 2. 2 分钟版本

### 为什么做 RAG

故障诊断不能只让模型自由发挥。AI FaultLab 的输入来自实验产生的 Evidence，包括指标、Trace 和规则诊断。RAG 的作用是把这些证据和对应 Runbook 结合起来，让模型参考结构化排查手册生成报告，同时保留引用可追溯性。

### 离线索引怎么做

Runbook 用 Markdown 存放，每个文档有 front matter，包括 `docId`、`faultType` 和 keywords。索引时按二级标题切成 section chunk，计算内容 hash。未变化文档直接 skip，变化文档会删除旧 chunk 后重新 embedding，并写入 Milvus。索引状态当前用本地 JSON 保存。

### 在线检索怎么做

诊断时 Java Backend 把 Evidence Package 发给 Python AI Service。AI Service 从 ruleResult、metrics、trace summary 和 scenarioCode 构造 query，同时走 Milvus 向量召回和 BM25-like 关键词召回。两路结果用 RRF 融合，再经过 lightweight rule-based rerank，最终选 topK chunk 注入 Prompt。

### fallback 怎么做

如果 Milvus 或 embedding 不可用，Hybrid 仍尝试 BM25-like keyword retrieval。如果 Hybrid 整体失败，RetrievalService 会退到 KeywordRunbookRetriever。如果 LLM 不可用或返回非法 JSON，workflow 会返回 rule-based fallback report。模型返回的 runbookReferences 还会被服务端过滤，只保留本次召回里的 `docId + section`。

### evaluation 怎么做

我加了 `rag_eval_cases.json`，当前 9 个 case，覆盖 MQ_BACKLOG、THREAD_POOL_SATURATION、IDEMPOTENCY_CONFLICT。评测 runner 可以分别跑 bm25、milvus、hybrid，计算 Hit@K、Recall@K、MRR，也可以生成 Markdown 报告，列出整体指标、按故障类型分组、case 明细、miss case 和规则型优化建议。

### 当前不足

当前还不是生产级 RAG 平台。BM25 是 BM25-like，不是标准搜索引擎级 BM25；rerank 是规则型，不是真实 rerank 模型；case 数量还少；没有 nDCG、可视化 UI、CI regression gate；索引状态还是本地 JSON，不适合多实例生产部署。

## 3. 面试官追问与回答

### Q1：为什么不直接把所有 Runbook 塞进 Prompt？

A：直接塞全部 Runbook 会浪费上下文窗口，引入噪声，也不方便扩展。更重要的是，它无法量化“到底有没有召回正确 section”。RAG 可以先检索相关 section，再把有限上下文交给 LLM，并用 Hit@K、Recall@K、MRR 评估检索质量。

### Q2：为什么先用 Markdown？

A：当前 Runbook 数量少、结构清晰，用 Markdown 可以直接 Git 管理、review 和回滚。第一版重点是打通检索、引用和评测闭环，不需要先做管理后台。

### Q3：为什么按 section 切？

A：Runbook 是结构化排查手册，二级标题 section 通常对应“核心指标”“常见原因”“排查步骤”“修复建议”等天然语义单元。按 section 切可以保持 chunk 可解释，也方便 `docId + section` 做引用校验。

### Q4：离线索引怎么避免重复写入？

A：索引时会计算文档 content hash，和本地 index state 对比。未变化文档 skip；变化文档会先删除旧 chunk，再重新 embedding 和 upsert；删除的 Runbook 会清理旧 chunk 和状态。

### Q5：为什么不用启动时自动索引？

A：索引依赖 embedding API、网络和 Milvus。如果启动时自动索引，会拖慢服务启动，也会让外部依赖失败影响 AI Service 可用性。当前选择显式索引接口，更适合演示和运维控制。

### Q6：为什么用 Milvus？

A：Runbook section 被向量化后需要语义召回能力。Milvus 负责向量存储和相似度搜索，可以召回和 query 语义相关但不完全同词的 chunk。

### Q7：为什么还要 BM25-like keyword retrieval？

A：故障诊断里有大量指标名、字段名和技术词，比如 `publishCount`、`rejectedTaskCount`、`redisSetNxFailCount`。这些词精确命中很重要，关键词召回能弥补纯向量召回对字段名不稳定的问题。

### Q8：你现在是不是标准 BM25？

A：不是。当前是 BM25-like keyword scoring，包含 TF、IDF、长度归一化和字段加权，但没有包装成标准搜索引擎级 BM25。

### Q9：RRF 是什么，为什么用它？

A：RRF 是 Reciprocal Rank Fusion，基于多个结果列表中的排名做融合。因为向量检索分数和关键词检索分数尺度不同，直接加权不稳；RRF 不依赖分数绝对值，更适合多路召回的第一版融合。

### Q10：rerank 是怎么做的？

A：当前是 lightweight rule-based rerank，不调用真实 rerank 模型。规则包括 faultType 是否匹配、section 是否属于排查/修复/常见原因这类重要 section、Evidence 和 metric 是否命中、是否被 Milvus 和 BM25-like 双路召回。

### Q11：如果 Milvus 挂了怎么办？

A：Hybrid 内部会捕获 vector retrieval 异常，并继续使用 BM25-like 结果。如果 Hybrid 整体失败，RetrievalService 还会 fallback 到 KeywordRunbookRetriever，目标是不让诊断接口因为 RAG 失败直接 500。

### Q12：如何防止模型编造引用？

A：Prompt 里要求 runbookReferences 只能引用 Runbook Context 中的 `docId + section`。服务端还会二次校验，只保留本次召回 chunk 中存在的 `docId + section`，不合法的引用会被过滤。

### Q13：怎么评估 RAG 效果？

A：使用 `rag_eval_cases.json` 定义 query 和 expected `docId + section`，评测 bm25、milvus、hybrid 的 Hit@K、Recall@K、MRR。CLI 和 API 都能跑评测，并能生成 Markdown Evaluation Report。

### Q14：当前有哪些不足？

A：当前不是标准 BM25，没有真实 rerank 模型；评测 case 数量少；没有 nDCG；没有 CI gate；没有可视化评测 UI；索引状态还是本地 JSON，不适合多实例生产部署。

## 4. 简历写法

- 设计并实现 AI FaultLab Runbook RAG 链路：支持 Markdown section chunking、百炼 embedding、Milvus 向量索引、Prompt 注入 Runbook Context 和 `runbookReferences` 校验。
- 实现 Hybrid Retrieval：结合 Milvus vector retrieval 与 BM25-like keyword retrieval，通过 RRF fusion 和 lightweight rule-based rerank 输出 topK Runbook chunk，并提供 keyword-only fallback。
- 构建 RAG Retrieval Evaluation：基于 9 个故障检索 case 计算 Hit@K、Recall@K、MRR，支持 API/CLI 评测和 Markdown 报告输出，用于分析 miss case 和检索调优。

## 5. 项目亮点总结

### Evidence-driven

诊断输入来自真实故障演练产生的 Metrics、Trace 和 Rule Diagnosis，而不是让模型自由猜测。

### Hybrid Retrieval

同时使用语义召回和关键词召回，兼顾自然语言表达与指标名、字段名的精确匹配。

### Index Management

通过 content hash、index state、skip、forceRebuild、旧 chunk 清理实现基础索引治理。

### Fallback

Milvus、Hybrid 和 LLM 都有降级路径，避免增强能力失败时影响主诊断流程。

### Evaluation

通过 Hit@K、Recall@K、MRR 和 Markdown Report，把检索效果从“主观感觉”变成可观察的工程指标。
