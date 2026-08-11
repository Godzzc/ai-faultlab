---
docId: cache-penetration
title: 缓存穿透故障 Runbook
faultType: CACHE_PENETRATION
keywords:
  - 缓存穿透
  - cache penetration
  - invalid key
  - cache miss
  - db query
  - null cache
  - bloom filter
  - cuckoo filter
  - cache.db.query.count
  - cache.invalid.key.count
  - cache.bloom.reject.count
---

# 缓存穿透故障 Runbook

## 现象

缓存穿透（cache penetration）通常表现为大量 invalid key 或不存在的数据请求持续 cache miss，Redis 查不到，DB query 也查不到，最终请求绕过缓存直接压到数据库。工程上常见描述包括 nonexistent key、empty result miss、negative lookup、DB 被无效查询打满。

在 AI FaultLab 中重点看 `cache.request.count`、`cache.miss.count`、`cache.miss.rate`、`cache.db.query.count` 和 `cache.invalid.key.count`。如果 `cache.bloom.reject.count` 为 0 且 `cache.null.cache.write.count` 为 0，说明 null cache 或 bloom filter 治理没有生效。

## 核心指标

- `cache.request.count`: 总请求数，request count。
- `cache.hit.count`: 缓存命中数，cache hit count。
- `cache.miss.count`: 缓存未命中数，cache miss count。
- `cache.miss.rate`: 未命中率，cache miss rate。
- `cache.db.query.count`: DB 查询次数，DB query count，穿透压力的核心指标。
- `cache.db.query.rate`: DB 查询占比，DB query rate。
- `cache.invalid.key.count`: 不存在 key 数量，invalid key count。
- `cache.null.cache.write.count`: 空值缓存写入次数，null cache write count。
- `cache.bloom.reject.count`: 过滤器拦截次数，bloom filter reject count。
- `cache.avg.db.query.ms`: 平均 DB 查询耗时，average DB query latency。

排查时先确认 `cache.miss.rate` 是否高，再看 `cache.db.query.count` 是否接近 `cache.request.count`，最后用 `cache.invalid.key.count` 判断是否是非法 key 或参数枚举导致的穿透。

## 常见原因

1. 参数没有校验，用户或脚本传入明显非法的 id、item code、tenant id，形成 invalid key。
2. 不存在数据没有写 null cache，导致同一个 empty result 每次都 miss Redis 并访问 DB。
3. 未部署 bloom filter 或 cuckoo filter，所有 key 都直接进入 Redis/DB lookup。
4. filter 数据更新滞后，新合法 key 还没加入 filter，被误判拦截；或者删除后的 key 仍被放行。
5. 热点非法 key 没有限流或黑名单，少量 key 也能制造大量 DB query spike。

## 排查步骤

1. 查看 `cache.invalid.key.count`、`cache.miss.rate`、`cache.db.query.count`，确认是否是大量不存在 key 造成 DB 压力。
2. 抽样访问日志，检查 key pattern，例如 `item:invalid:*`、空 id、负数 id、越权 tenant id。
3. 检查是否启用了 null cache，关注 `cache.null.cache.write.count` 是否随 invalid key 增加。
4. 检查 filter 拦截效果，关注 `cache.bloom.reject.count`，确认 bloom filter / cuckoo filter 是否加载了合法 key 集。
5. 排查 DB 慢查询和连接池，确认 `cache.avg.db.query.ms` 是否因穿透被放大。

## 修复建议

优先做入口参数校验，拒绝明显非法 key；对 DB 返回空的结果写 null cache，并设置短 TTL，避免永久缓存空值影响后续真实数据创建。对 key 空间明确的业务使用 bloom filter 或 cuckoo filter，配合异步增量更新和定期重建。

对热点非法 key 增加 rate limit、黑名单或 WAF 规则。高风险接口可以按用户、IP、tenant、key 维度限流。对于 null cache，要控制 TTL，常见为几十秒到几分钟，并在真实数据写入时主动删除空值缓存。

## 风险提示

null cache TTL 不能过长，否则新数据创建后仍可能返回空结果。Bloom filter 有误判和更新问题：误判会拒绝合法 key，更新滞后会让新 key 被拦截。不要只靠缓存保护数据库，参数校验、限流、黑名单和 DB 连接池保护需要同时存在。

