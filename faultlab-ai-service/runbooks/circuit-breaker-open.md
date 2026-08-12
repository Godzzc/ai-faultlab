---
docId: circuit-breaker-open
title: 熔断器打开故障 Runbook
faultType: CIRCUIT_BREAKER_OPEN
keywords:
  - 熔断器打开
  - circuit breaker open
  - circuit open
  - half open
  - fallback
  - rejected request
  - failure rate
  - slow call rate
  - circuit.open.count
  - circuit.rejected.count
  - circuit.fallback.count
  - downstream.call.skipped.count
---

# 熔断器打开故障 Runbook

## 现象

熔断器打开（circuit breaker open）表示下游失败率或慢调用比例超过阈值，熔断器从 CLOSED 进入 OPEN，后续请求不再访问下游，而是快速 rejected request 或进入 fallback。工程上常见表达包括 circuit open、closed/open/half-open state、recovery probe、failure rate threshold、slow call rate threshold。

在 AI FaultLab 中重点观察 `circuit.failure.rate`、`circuit.slow.call.rate`、`circuit.open.count`、`circuit.rejected.count`、`circuit.fallback.count` 和 `downstream.call.skipped.count`。如果 `circuit.open.count > 0` 且 `downstream.call.skipped.count` 上升，说明熔断已经阻止请求继续打向下游。

## 核心指标

- `circuit.request.count`: 熔断器评估请求数，circuit request count。
- `circuit.failure.count`: 下游失败次数，circuit failure count。
- `circuit.failure.rate`: 失败率，failure rate。
- `circuit.slow.call.count`: 慢调用次数，slow call count。
- `circuit.slow.call.rate`: 慢调用比例，slow call rate。
- `circuit.open.count`: 熔断打开次数，circuit open count。
- `circuit.half.open.count`: 半开探测次数，half-open probe count。
- `circuit.rejected.count`: 熔断拒绝请求数，rejected request count。
- `circuit.fallback.count`: 熔断 fallback 次数，fallback count。
- `downstream.call.skipped.count`: 被跳过的下游调用数，downstream call skipped count。
- `api.error.count`: API 错误数，API error count。

排查时先看 `circuit.failure.rate` 和 `circuit.slow.call.rate` 是否超过阈值，再看 `circuit.open.count`、`circuit.rejected.count`、`circuit.fallback.count` 判断 OPEN 后是快速失败还是降级返回。

## 常见原因

1. 下游持续失败，滑动窗口内 failure rate 超过 failure rate threshold。
2. 下游未完全失败但响应慢，slow call rate 超过慢调用阈值。
3. 熔断阈值过低或滑动窗口过小，少量失败就触发 circuit open。
4. fallback 缺失，OPEN 后请求直接 rejected 并计入 API error。
5. 半开恢复探测策略不合理，HALF_OPEN 过早放量或探测请求不足。

## 排查步骤

1. 查看 `circuit.failure.rate`、`circuit.slow.call.rate`、`circuit.open.count`，确认熔断触发原因。
2. 查看 `circuit.rejected.count` 和 `downstream.call.skipped.count`，确认 OPEN 后是否停止访问下游。
3. 检查 `circuit.fallback.count` 与 `api.error.count`，判断 fallback 是否减少用户可见错误。
4. 复查 sliding window、failure rate threshold、slow call threshold、open duration 和 half-open 探测配置。
5. 检查下游真实健康状况，避免只关注熔断器而忽略下游错误率、慢查询、线程池或连接池问题。

## 修复建议

根据接口重要性和历史失败率配置合理 failure rate threshold、slow call rate、sliding window size 和 open duration。为可降级业务增加 fallback，OPEN 后返回缓存、默认值、排队状态或静态结果。

隔离下游调用线程池，限制重试，避免重试风暴和熔断反复震荡。HALF_OPEN 阶段应使用少量 recovery probe 验证下游恢复，再逐步恢复流量。对关键下游同时监控 `circuit.open.count`、`circuit.rejected.count`、`downstream.call.skipped.count` 和业务成功率。

## 风险提示

熔断阈值过低会误伤正常流量，阈值过高会让故障流量继续打爆下游。fallback 可能返回旧值或降级结果，需要在产品层明确用户体验。HALF_OPEN 探测过快可能造成抖动，过慢会延长恢复时间。熔断不是修复下游故障，只是限制故障传播。
