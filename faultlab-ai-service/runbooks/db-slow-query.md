---
docId: db-slow-query
title: 慢 SQL 故障 Runbook
faultType: DB_SLOW_QUERY
keywords:
  - 慢 SQL
  - slow query
  - full scan
  - full table scan
  - scanned rows
  - index miss
  - execution plan
  - covering index
  - db.slow.query.count
  - db.avg.query.ms
  - db.full.scan.count
  - db.scanned.rows
---

# 慢 SQL 故障 Runbook

## 现象

慢 SQL（slow query）通常表现为接口响应变慢、DB 查询耗时升高、扫描行数 scanned rows 过多，常见工程表达包括 full scan、full table scan、index miss、execution plan 异常、分页越深越慢。查询可能没有命中合适索引，或因为函数、隐式转换、低选择性条件导致 index invalidation。

在 AI FaultLab 中重点看 `db.slow.query.count`、`db.avg.query.ms`、`db.max.query.ms`、`db.full.scan.count`、`db.scanned.rows`、`db.table.size` 和 `api.avg.latency.ms`。如果 `db.scanned.rows` 接近 `db.table.size`，同时 `db.full.scan.count` 高，通常说明全表扫描正在放大查询耗时。

## 核心指标

- `db.query.count`: DB 查询总数，query count。
- `db.slow.query.count`: 慢查询次数，slow query count。
- `db.slow.query.rate`: 慢查询占比，slow query rate。
- `db.avg.query.ms`: 平均查询耗时，average query latency。
- `db.max.query.ms`: 最大查询耗时，max query latency。
- `db.full.scan.count`: 全表扫描次数，full scan count。
- `db.index.hit.count`: 索引命中次数，index hit count。
- `db.scanned.rows`: 扫描行数，scannedRows / scanned rows。
- `db.table.size`: 表数据量，table size。
- `api.avg.latency.ms`: 接口平均延迟，API average latency。

排查时先确认 `db.slow.query.count` 和 `db.avg.query.ms` 是否升高，再比较 `db.scanned.rows` 与 `db.table.size`，最后结合 `db.full.scan.count` 和 `db.index.hit.count` 判断是否为 index miss 或 full table scan。

## 常见原因

1. 查询条件没有合适索引，或组合索引字段顺序不匹配，导致 index miss。
2. 在索引列上使用函数、表达式、隐式类型转换，例如 `DATE(create_time)`、字符串列传数字，导致 index invalidation。
3. `LIKE '%keyword'`、低选择性字段、OR 条件不合理，优化器选择 full scan。
4. 深分页（deep pagination）使用大 offset，扫描和丢弃大量行。
5. `select *` 返回字段过多，无法使用 covering index，并增加网络和序列化开销。

## 排查步骤

1. 查看 `db.slow.query.count`、`db.avg.query.ms`、`db.max.query.ms`，确认慢查询影响范围。
2. 查看 `db.full.scan.count`、`db.scanned.rows`、`db.table.size`，判断是否 full table scan。
3. 对慢 SQL 执行 `EXPLAIN` 或查看 execution plan，重点看 type、key、rows、filtered、Extra。
4. 检查 WHERE 条件是否存在 function on indexed column、implicit conversion、低选择性字段或索引顺序错误。
5. 检查分页、排序、返回字段，确认是否可以改为 seek pagination、覆盖索引或只返回必要列。

## 修复建议

优先为高频过滤条件建立合适索引，组合索引按等值条件、范围条件、排序字段顺序设计。避免在索引列上使用函数或隐式转换，必要时新增冗余字段或生成列承接查询条件。

减少 `select *`，只返回业务需要字段，尽量使用 covering index。深分页改为基于游标或主键的 seek pagination。对复杂查询拆分为先查 id 再回表，或使用异步离线汇总表。上线前用 `EXPLAIN`、慢查询日志和压测样本验证 execution plan。

## 风险提示

加索引会增加写入成本和磁盘占用，不能无边界堆索引。覆盖索引字段过多会降低维护效率。强行指定索引可能在数据分布变化后变差。慢 SQL 常会连带造成连接池占用、锁等待和接口超时，需要同时观察 `db.connection.hold.avg.ms`、`db.pool.active.count` 和 API 延迟。
