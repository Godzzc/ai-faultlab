---
docId: downstream-timeout
title: 下游接口超时故障 Runbook
faultType: DOWNSTREAM_TIMEOUT
keywords:
  - 下游接口超时
  - downstream timeout
  - downstream latency
  - slow downstream
  - timeoutMs
  - fallback
  - timeout rate
  - downstream.timeout.count
  - downstream.timeout.rate
  - downstream.avg.latency.ms
  - downstream.max.latency.ms
  - api.error.count
---

# 下游接口超时故障 Runbook

## 现象

下游接口超时（downstream timeout）通常表现为当前服务响应变慢、请求耗时接近或超过 `timeoutMs`、部分请求失败，或者在启用 fallback 后返回降级结果。工程上常见描述包括 slow downstream、downstream latency spike、timeout config too short、thread blocked by remote call、service degradation。

在 AI FaultLab 中重点观察 `downstream.timeout.count`、`downstream.timeout.rate`、`downstream.avg.latency.ms`、`downstream.max.latency.ms`、`downstream.slow.call.count` 和 `api.error.count`。如果 `downstreamDelayMs` 高于 `timeoutMs` 且 `enableFallback=false`，API 错误会随 timeout count 上升；启用 fallback 后应看到 `downstream.fallback.count` 增加且 `api.error.count` 降低。

## 核心指标

- `downstream.request.count`: 下游调用请求总数，downstream request count。
- `downstream.timeout.count`: 下游超时次数，downstream timeout count。
- `downstream.timeout.rate`: 超时比例，timeout rate。
- `downstream.avg.latency.ms`: 下游平均延迟，average downstream latency。
- `downstream.max.latency.ms`: 下游最大延迟，max downstream latency。
- `downstream.slow.call.count`: 慢调用次数，slow downstream call count。
- `downstream.fallback.count`: fallback 执行次数，fallback count。
- `api.avg.latency.ms`: 当前接口平均延迟，API average latency。
- `api.error.count`: 当前接口错误数，API error count。
- `api.success.count`: 当前接口成功数，API success count。

排查时先看 `downstream.timeout.rate` 是否高，再看 `downstream.max.latency.ms` 是否明显超过 `timeoutMs`，最后用 `downstream.fallback.count` 和 `api.error.count` 判断 fallback 是否起到降级保护。

## 常见原因

1. 下游服务慢查询、锁等待、连接池耗尽或线程池饱和，导致 slow downstream。
2. 调用方 `timeoutMs` 配置过短或缺少按接口分级的 timeout config。
3. 当前服务同步等待下游，缺少 bulkhead isolation 或 thread pool isolation，导致调用线程被慢接口占满。
4. fallback 未启用或 fallback 自身访问慢依赖，超时后直接转为 API error。
5. 下游网络抖动、DNS 或网关代理异常，造成 downstream latency 波动。

## 排查步骤

1. 查看 `downstream.timeout.count`、`downstream.timeout.rate`、`api.error.count`，确认错误是否由超时驱动。
2. 对比 `downstream.avg.latency.ms`、`downstream.max.latency.ms` 和 `timeoutMs`，判断是偶发峰值还是整体慢调用。
3. 检查调用链 Trace 中的 `downstream.call`、`downstream.timeout`、`fallback.execute`，确认时间消耗发生在哪个环节。
4. 查看是否启用 fallback，并确认 `downstream.fallback.count` 是否随 timeout count 增加。
5. 检查下游自身监控：慢 SQL、线程池、连接池、错误率、GC、网关耗时和限流日志。

## 修复建议

按接口重要性和历史延迟设置合理 timeout config，不要对所有下游统一使用过大或过小的超时。为慢下游增加 fallback 和 service degradation，返回默认值、本地缓存、静态兜底或可重试的业务状态。

对高风险下游调用使用 bulkhead isolation 或 thread pool isolation，避免慢调用占满主业务线程。必要时引入熔断降级、限流、异步化和减少同步依赖。对下游慢接口应结合自身指标排查 SQL、连接池、锁等待和容量瓶颈。

## 风险提示

timeoutMs 过短会放大误判，timeoutMs 过长会拖垮调用方线程。fallback 不能掩盖核心业务失败，必须区分可降级和不可降级接口。线程池隔离需要容量评估，池太小会造成误拒绝，池太大又会把压力继续传给下游。不要只在调用方加超时，还要定位下游真实慢点。
