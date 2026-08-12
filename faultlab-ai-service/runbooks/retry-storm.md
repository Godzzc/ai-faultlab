---
docId: retry-storm
title: 重试风暴故障 Runbook
faultType: RETRY_STORM
keywords:
  - 重试风暴
  - retry storm
  - retry amplification
  - retry budget
  - exponential backoff
  - jitter
  - maxRetries
  - retry exhausted
  - downstream.retry.count
  - downstream.total.call.count
  - downstream.retry.amplification.factor
---

# 重试风暴故障 Runbook

## 现象

重试风暴（retry storm）通常发生在下游失败、超时或限流后，上游继续按 `maxRetries` 重试，导致 downstream total calls 远大于 initial request count。工程上常见表达包括 retry amplification、retry exhausted、no retry budget、multi-layer retry、without jitter、retry traffic spike。

在 AI FaultLab 中重点观察 `downstream.initial.request.count`、`downstream.total.call.count`、`downstream.retry.count`、`downstream.retry.rate`、`downstream.retry.exhausted.count` 和 `downstream.retry.amplification.factor`。如果 amplification factor 明显大于 1，说明重试正在放大下游压力。

## 核心指标

- `downstream.initial.request.count`: 初始上游请求数，initial request count。
- `downstream.total.call.count`: 下游总调用数，total downstream calls。
- `downstream.retry.count`: 重试次数，retry count。
- `downstream.retry.rate`: 重试比例，retry rate。
- `downstream.retry.exhausted.count`: 重试耗尽次数，retry exhausted count。
- `downstream.failure.count`: 下游失败调用数，downstream failure count。
- `downstream.success.count`: 成功请求数，downstream success count。
- `downstream.retry.amplification.factor`: 重试放大倍数，retry amplification factor。
- `api.error.count`: API 错误数，API error count。
- `api.avg.latency.ms`: API 平均延迟，API average latency。

排查时先看 `downstream.retry.amplification.factor`，再看 `downstream.retry.count` 和 `downstream.retry.exhausted.count`，最后结合 `failureRatio`、`maxRetries`、`enableRetryLimit` 和 `enableJitter` 判断重试策略是否失控。

## 常见原因

1. `maxRetries` 设置过高，下游失败时每个请求都按最大次数重试。
2. 没有 retry budget 或 retry limit，导致失败流量被持续放大。
3. 缺少 exponential backoff 和 jitter，重试集中在同一时间窗口打向下游。
4. 多层服务同时重试，形成 multi-layer retry amplification。
5. 对非幂等接口进行重试，导致重复写入、状态冲突或补偿复杂度上升。

## 排查步骤

1. 对比 `downstream.initial.request.count` 和 `downstream.total.call.count`，确认调用量是否被重试放大。
2. 查看 `downstream.retry.count`、`downstream.retry.rate`、`downstream.retry.exhausted.count`，判断重试是否仍无法恢复。
3. 检查调用配置中的 `maxRetries`、`retryBackoffMs`、`enableRetryLimit` 和 `enableJitter`。
4. 沿调用链确认是否存在多层服务同时重试，尤其是网关、SDK、业务服务和消息消费者重复配置 retry。
5. 校验接口是否幂等，非幂等写接口需要避免自动重试或引入幂等键。

## 修复建议

限制重试次数，并使用 retry budget 控制单位时间内允许的额外重试流量。对可重试错误使用 exponential backoff 和 jitter，避免所有请求在相同时间点重试。只对幂等请求或有幂等保护的写请求重试。

当下游失败率持续升高时，应优先触发 circuit breaker 或 fallback，而不是继续扩大重试。统一治理多层重试策略，避免网关、SDK、服务内部和 MQ 消费端叠加重试。对核心链路设置重试监控，报警条件应包含 `downstream.retry.amplification.factor` 和 `downstream.retry.exhausted.count`。

## 风险提示

重试会提升偶发失败的成功率，但会在持续故障时快速放大流量。指数退避和 jitter 不能替代熔断，只能降低集中冲击。retry budget 过低可能影响短暂抖动恢复，过高会继续打爆下游。非幂等重试可能造成重复扣款、重复创建订单或状态覆盖。
