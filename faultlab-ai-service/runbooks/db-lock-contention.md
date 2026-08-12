---
docId: db-lock-contention
title: 数据库锁竞争故障 Runbook
faultType: DB_LOCK_CONTENTION
keywords:
  - 数据库锁竞争
  - lock contention
  - row lock
  - long transaction
  - lock wait
  - transaction blocking
  - hot row
  - db.lock.wait.count
  - db.avg.lock.wait.ms
  - db.update.timeout.count
  - db.long.transaction.count
---

# 数据库锁竞争故障 Runbook

## 现象

数据库锁竞争（lock contention）通常表现为多个请求更新同一 hot row，或 long transaction 长时间持有 row lock，其他事务出现 lock wait、transaction blocking、更新超时和接口响应变慢。工程上常见描述包括热点行更新阻塞、锁等待超时、事务范围过大、事务内远程调用。

在 AI FaultLab 中重点看 `db.lock.wait.count`、`db.lock.wait.rate`、`db.avg.lock.wait.ms`、`db.max.lock.wait.ms`、`db.update.timeout.count`、`db.long.transaction.count`、`db.transaction.active.count` 和 `api.timeout.count`。

## 核心指标

- `db.lock.request.count`: 请求锁的次数，lock request count。
- `db.lock.wait.count`: 发生锁等待的次数，lock wait count。
- `db.lock.wait.rate`: 锁等待占比，lock wait rate。
- `db.lock.wait.ms`: 锁等待总耗时，total lock wait time。
- `db.avg.lock.wait.ms`: 平均锁等待耗时，average lock wait latency。
- `db.max.lock.wait.ms`: 最大锁等待耗时，max lock wait latency。
- `db.long.transaction.count`: 长事务数量，long transaction count。
- `db.transaction.active.count`: 活跃事务数量，active transaction count。
- `db.update.success.count`: 更新成功数量，update success count。
- `db.update.timeout.count`: 更新超时数量，update timeout count。
- `api.timeout.count`: API 超时数量，API timeout count。

排查时重点看 `db.update.timeout.count` 是否大于 0，再看 `db.long.transaction.count` 和 `db.avg.lock.wait.ms`，确认是否是长事务持锁导致请求排队。

## 常见原因

1. 事务范围过大，业务逻辑、远程调用、消息发送或文件处理放在事务内。
2. 多个请求集中更新同一 hot row，例如同一订单、库存汇总行、账户余额行。
3. SQL 更新顺序不一致，多个事务交叉锁定资源，增加 blocking 和死锁风险。
4. 缺少乐观锁或幂等保护，重复提交不断争抢同一行锁。
5. 锁等待超时配置不合理，问题发生时请求长时间挂起，占用线程和连接。

## 排查步骤

1. 查看 `db.lock.wait.count`、`db.avg.lock.wait.ms`、`db.update.timeout.count`，判断是否已有明显 lock wait timeout。
2. 按业务 key 检查 hot row，例如 orderId、accountId、skuId 是否集中。
3. 查看数据库事务和锁等待视图，确认 blocking transaction、等待 SQL 和持锁 SQL。
4. 检查事务代码，确认是否存在事务内远程调用、循环更新、批量处理或用户交互等待。
5. 核对更新顺序和索引条件，避免无索引更新扩大锁范围。

## 修复建议

缩短事务范围，只把必要的 DB 写入放进事务，远程调用、MQ 发送、缓存刷新和文件处理移出事务。对热点行拆分维度或分片更新，降低单行锁竞争。对可接受冲突重试的业务使用 optimistic lock、版本号或 CAS。

统一多表多行更新顺序，减少死锁和 transaction blocking。为更新条件建立索引，避免无索引更新扩大锁范围。设置合理 lock wait timeout，并在应用层提供重试、降级或排队机制。

## 风险提示

缩短事务不能破坏一致性边界，拆分热点行需要设计聚合读取和补偿逻辑。乐观锁会把等待变成失败重试，高并发下要控制重试次数和退避。降低锁等待超时能更快失败，但可能增加业务失败率，需要配合用户提示和补偿流程。
