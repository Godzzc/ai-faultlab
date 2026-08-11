---
docId: cache-breakdown
title: 缓存击穿故障 Runbook
faultType: CACHE_BREAKDOWN
keywords:
  - 缓存击穿
  - cache breakdown
  - hot key
  - hotKey
  - cache rebuild
  - mutex
  - logical expire
  - singleflight
  - cache.hot.key.miss.count
  - cache.rebuild.count
  - cache.lock.acquire.count
---

# 缓存击穿故障 Runbook

## 现象

缓存击穿（cache breakdown）发生在 hot key expired 后，大量并发请求同时访问同一个热点 key。Redis miss 后请求集中进入 DB query hot key，并触发重复 cache rebuild，工程上常称为 rebuild storm、hot key breakdown、thundering herd。

在 AI FaultLab 中，典型现象是 `cache.hot.key.request.count` 很高，`cache.hot.key.miss.count` 突增，`cache.db.query.count` 和 `cache.rebuild.count` 同时升高。未启用 mutex lock 或 logical expire 时，`cache.concurrent.rebuild.count` 会显示重复重建压力。

## 核心指标

- `cache.hot.key.request.count`: 热点 key 请求数，hot key request count。
- `cache.hot.key.miss.count`: 热点 key 未命中数，hot key miss count。
- `cache.miss.rate`: 未命中率，cache miss rate。
- `cache.db.query.count`: DB 查询次数，DB query count。
- `cache.rebuild.count`: 缓存重建次数，cache rebuild count。
- `cache.lock.acquire.count`: 互斥锁获取次数，mutex lock acquire count。
- `cache.lock.fail.count`: 互斥锁失败次数，lock fail count，说明请求被挡在重建之外。
- `cache.rebuild.duration.ms`: 重建耗时，rebuildDurationMs。
- `cache.concurrent.rebuild.count`: 并发重建数，concurrent rebuild count。

排查时重点比较 `cache.rebuild.count` 和 `cache.lock.acquire.count`。如果 rebuild count 接近请求量，说明 singleflight 或互斥重建没有生效。

## 常见原因

1. hot key 设置了普通 TTL，到期瞬间所有请求同时 cache miss。
2. cache rebuild 太慢，`cache.rebuild.duration.ms` 高，重建窗口内持续有请求打 DB。
3. 没有 mutex lock、singleflight 或请求合并机制，导致重复 DB query 和重复 rebuild。
4. 逻辑过期（logical expire）未启用，无法先返回 stale value 再异步刷新。
5. 热点 key 没有预热或永不过期策略，流量高峰时直接失效。

## 排查步骤

1. 确认问题 key 是否为 hot key，查看 `cache.hot.key.request.count` 和访问日志中的 hotKey。
2. 查看 `cache.hot.key.miss.count` 与过期时间，判断是否是 hot key expired。
3. 对比 `cache.db.query.count`、`cache.rebuild.count`、`cache.concurrent.rebuild.count`，确认是否发生 rebuild storm。
4. 检查 `cache.lock.acquire.count` 和 `cache.lock.fail.count`，确认 mutex lock 是否只允许一个请求重建。
5. 检查重建逻辑耗时，定位 `cache.rebuild.duration.ms` 中的慢 SQL、远程调用或序列化开销。

## 修复建议

对热点 key 使用互斥锁重建、singleflight 或请求合并，确保同一时刻只有一个请求查询 DB 并 cache rebuild。读多写少的热点数据可以使用 logical expire：请求先返回 stale value，后台异步刷新，避免用户请求阻塞在 DB。

对核心 hot key 可采用永不过期加异步刷新、定时预热、本地缓存兜底。重建逻辑需要设置超时和降级，避免锁持有时间过长。锁 key 要有短 TTL，防止重建线程异常后死锁。

## 风险提示

mutex lock 会降低 DB 压力，但可能增加尾延迟；logical expire 会返回 stale value，需要业务接受短时间旧数据。热点 key 永不过期要配合主动失效或版本号，否则数据变更后可能长期不一致。singleflight 只能合并同进程请求，多实例仍需要分布式锁或二级缓存。

