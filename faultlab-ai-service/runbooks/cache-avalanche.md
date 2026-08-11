---
docId: cache-avalanche
title: 缓存雪崩故障 Runbook
faultType: CACHE_AVALANCHE
keywords:
  - 缓存雪崩
  - cache avalanche
  - same ttl
  - ttl jitter
  - redis down
  - cache unavailable
  - fallback
  - cache.expired.key.count
  - cache.unavailable.count
  - cache.request.error.count
---

# 缓存雪崩故障 Runbook

## 现象

缓存雪崩（cache avalanche）通常表现为 many keys expired at the same time，或 Redis unavailable / redis down 导致大面积 cache unavailable。请求无法从缓存返回，转而集中访问数据库，形成 DB query spike、慢查询和 request error count 上升。

在 AI FaultLab 中，重点观察 `cache.expired.key.count`、`cache.unavailable.count`、`cache.miss.rate`、`cache.db.query.count`、`cache.db.slow.query.count`、`cache.fallback.count` 和 `cache.request.error.count`。

## 核心指标

- `cache.key.count`: 缓存 key 总数，cache key count。
- `cache.expired.key.count`: 同时失效 key 数，expired key count。
- `cache.unavailable.count`: 缓存不可用次数，cache unavailable count。
- `cache.request.count`: 请求总数，request count。
- `cache.hit.count`: 缓存命中数，cache hit count。
- `cache.miss.count`: 缓存未命中数，cache miss count。
- `cache.miss.rate`: 未命中率，cache miss rate。
- `cache.db.query.count`: DB 查询次数，DB query count。
- `cache.db.slow.query.count`: DB 慢查询次数，slow DB query count。
- `cache.fallback.count`: fallback 或本地缓存兜底次数。
- `cache.request.error.count`: 请求错误数，request error count。

排查时先看 `cache.expired.key.count` 是否接近 `cache.key.count`，再看 `cache.unavailable.count` 是否为 Redis unavailable，最后看 fallback 是否覆盖了错误。

## 常见原因

1. 大批 key 使用 same TTL，在同一时间过期，形成 many keys expired。
2. 没有 TTL jitter，缓存过期时间集中，导致瞬时 cache miss rate 升高。
3. Redis 节点不可用、网络抖动、连接池耗尽或主从切换，造成 cache unavailable。
4. 缺少 fallback、本地缓存、多级缓存或熔断，缓存不可用时请求直接打 DB。
5. 预热不足，发布或重启后大量 key 同时 cold start。

## 排查步骤

1. 查看 `cache.expired.key.count`、`cache.key.count` 和 key TTL 分布，确认是否 same TTL。
2. 查看 `cache.unavailable.count` 和 Redis 客户端错误，确认是否 redis down、timeout、connection refused。
3. 查看 `cache.db.query.count` 与 `cache.db.slow.query.count`，判断数据库是否被缓存 miss 放大。
4. 查看 `cache.fallback.count` 与 `cache.request.error.count`，确认 fallback 是否生效。
5. 检查发布、预热、批量写缓存任务，确认是否把大量 key 写成相同过期时间。

## 修复建议

对批量 key 使用 TTL jitter，例如基础 TTL 加随机偏移，避免 same TTL 同时过期。上线前做 cache warm-up，热点数据提前加载。对核心读接口增加 multi-level cache，包括本地缓存、Redis、DB 的分层兜底。

当 Redis unavailable 时启用 fallback、rate limit、circuit breaker 和降级默认值，避免 DB 被瞬时流量打垮。Redis 层面应配置 Redis HA、主从/哨兵/集群、连接池隔离和超时保护。

## 风险提示

TTL jitter 只能降低同时过期概率，不能解决 Redis down。Fallback 数据可能过期或不完整，需要明确业务可接受范围。限流和熔断会保护系统但可能牺牲部分请求成功率。缓存预热要控制速率，避免预热本身打爆 DB。

