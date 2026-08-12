---
docId: db-connection-pool-exhaustion
title: 数据库连接池耗尽故障 Runbook
faultType: DB_CONNECTION_POOL_EXHAUSTION
keywords:
  - 数据库连接池耗尽
  - connection pool exhausted
  - HikariCP
  - active connection
  - idle connection
  - acquire timeout
  - connection leak
  - db.pool.active.count
  - db.connection.acquire.timeout.count
  - db.connection.acquire.avg.ms
  - db.connection.hold.avg.ms
---

# 数据库连接池耗尽故障 Runbook

## 现象

数据库连接池耗尽（connection pool exhausted）通常表现为 HikariCP active connection 接近 maxPoolSize，idle connection 归零，新请求 acquire connection 等待或 acquire timeout。慢查询、长事务、连接泄漏 connection leak 都会让连接 hold 太久，最终出现 API error 或请求排队。

在 AI FaultLab 中重点看 `db.pool.max.size`、`db.pool.active.count`、`db.pool.idle.count`、`db.connection.acquire.timeout.count`、`db.connection.acquire.timeout.rate`、`db.connection.acquire.avg.ms`、`db.connection.hold.avg.ms`、`db.query.count` 和 `api.error.count`。

## 核心指标

- `db.pool.max.size`: 连接池最大连接数，maxPoolSize。
- `db.pool.active.count`: 活跃连接数，active connection count。
- `db.pool.idle.count`: 空闲连接数，idle connection count。
- `db.connection.acquire.count`: 获取连接请求数，connection acquire count。
- `db.connection.acquire.timeout.count`: 获取连接超时数，acquire timeout count。
- `db.connection.acquire.timeout.rate`: 获取连接超时率，acquire timeout rate。
- `db.connection.acquire.avg.ms`: 平均获取连接耗时，average acquire latency。
- `db.connection.hold.avg.ms`: 平均连接持有耗时，average connection hold time。
- `db.query.count`: 成功执行查询数，query count。
- `api.error.count`: API 错误数量，API error count。

排查时先看 `db.pool.active.count` 是否接近 `db.pool.max.size`，再看 `db.pool.idle.count` 是否为 0，最后用 `db.connection.acquire.timeout.count` 和 `db.connection.hold.avg.ms` 判断是容量不足、慢查询占用还是连接泄漏。

## 常见原因

1. slow query holds connection，慢 SQL 长时间占用连接，吞吐下降。
2. long transaction holds connection，事务范围过大导致连接释放慢。
3. maxPoolSize 配置过小，不能承载当前并发和查询耗时。
4. connection leak，代码未关闭 ResultSet、Statement 或连接代理未释放。
5. 下游 DB 慢、网络抖动或锁等待导致连接持有时间升高，进而耗尽池。

## 排查步骤

1. 查看 `db.pool.active.count`、`db.pool.max.size`、`db.pool.idle.count`，确认连接池是否已满。
2. 查看 `db.connection.acquire.avg.ms` 和 `db.connection.acquire.timeout.count`，确认请求是否在等连接。
3. 查看 `db.connection.hold.avg.ms`，定位连接持有时间是否因慢 SQL 或长事务升高。
4. 检查 HikariCP 日志、leakDetectionThreshold、线程栈和慢查询日志，确认是否 connection leak。
5. 按接口、SQL、事务路径统计连接占用，找出占用连接最长的调用链。

## 修复建议

先优化慢 SQL 和长事务，缩短连接持有时间，再评估 pool sizing。合理配置 maxPoolSize、connectionTimeout、idleTimeout、maxLifetime，避免盲目扩大连接池把压力转移给数据库。

开启 HikariCP leak detection 辅助定位未释放连接。为读写流量隔离连接池，核心接口与后台任务分池，避免批处理耗尽在线请求连接。对获取连接超时增加降级兜底、限流或快速失败，保护线程池和上游请求。

## 风险提示

连接池不是越大越好，过大的 maxPoolSize 可能让数据库线程、CPU 和锁竞争恶化。只调大连接池但不修复 slow query、long transaction 或 connection leak，问题会再次出现。获取连接超时配置过长会拖垮应用线程池，过短会增加失败率，需要结合 SLA 设置。
