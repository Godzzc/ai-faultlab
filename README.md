# AI FaultLab

AI FaultLab 是一个面向 Java 后端故障排查场景的故障演练与智能诊断平台。

系统支持一键模拟 MQ 消息堆积、线程池饱和、幂等冲突等典型后端问题，并结合 Trace 调用链、运行指标、规则引擎、Runbook RAG 和 AI 诊断服务生成结构化诊断报告。

## 项目定位

本项目不是传统业务系统，也不是生产级 APM 平台，而是一个用于后端故障复现、链路观测和智能诊断的工程化实验平台。

核心流程：

```text
触发故障
  -> 采集 Trace / 指标
  -> 规则引擎初步诊断
  -> Runbook RAG 检索
  -> AI 生成诊断报告
```

## 核心功能

* MQ 消息堆积演练
* 线程池饱和 / 长任务阻塞演练
* 幂等冲突与一致性演练
* 轻量级 Trace 链路追踪
* Runbook RAG 检索
* AI 结构化诊断报告

## 技术栈

### 后端主服务

* Java 17
* Spring Boot 3.x
* MySQL
* Redis
* RabbitMQ

### AI 诊断服务

* Python
* FastAPI
* RAG
* 大模型 API

### 部署

* Docker
* Docker Compose
* Nginx

## 项目结构

```text
ai-faultlab
├── faultlab-backend        # Spring Boot 主服务
├── faultlab-ai-service     # Python AI 诊断服务
├── faultlab-frontend       # 前端页面
├── faultlab-trace-sdk      # 轻量级 Trace SDK
├── faultlab-runbook        # Runbook 故障知识库
├── deploy                  # Docker / Nginx 部署配置
├── docs                    # 项目文档
└── README.md
```

## 第一版计划

V1 重点完成三个可演示场景：

| 场景      | 说明                      |
| ------- | ----------------------- |
| MQ 消息堆积 | 模拟生产速度大于消费速度导致队列积压      |
| 线程池饱和   | 模拟长任务占满线程池导致任务排队        |
| 幂等冲突    | 模拟重复提交下 Redis 拦截和 DB 兜底 |

## 项目亮点

* 设计插件化故障场景模型，统一故障触发、证据采集、规则诊断和 AI 报告生成流程。
* 自研轻量级 Trace SDK，基于 traceId / spanId / parentSpanId 还原故障调用链。
* 结合规则引擎和 Runbook RAG，避免 AI 诊断完全依赖大模型猜测。
* Java 主服务负责故障演练与链路采集，Python AI 服务负责 RAG 检索和诊断报告生成。

## 本地启动

```bash
git clone https://github.com/your-username/ai-faultlab.git
cd ai-faultlab
```

后续支持：

```bash
cd deploy
docker compose up -d
```

## 后续规划

* 缓存击穿诊断
* 慢 SQL 诊断
* 消息回放与问题复现
* Agent 工具调用安全审计
* Java 21 虚拟线程对比实验

## License

MIT
