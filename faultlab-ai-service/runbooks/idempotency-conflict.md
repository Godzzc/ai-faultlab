---
docId: idempotency-conflict
title: 幂等冲突排查手册
faultType: IDEMPOTENCY_CONFLICT
keywords: idempotency, Idempotency-Key, requestHash, duplicate, conflict, SETNX, Redis, unique index, PROCESSING, SUCCESS, FAILED
---

# 幂等冲突排查手册

## 现象

幂等冲突通常出现在重复提交、网络重试、用户连续点击、前端超时重发等场景中。

在故障演练场景中，常见表现包括：

- requestCount 较高
- duplicateCount 大于 0
- rejectedDuplicateCount 大于 0
- hashMismatchCount 大于 0
- redisSetNxFailCount 大于 0
- 同一个 Idempotency-Key 对应不同 requestHash
- 多次请求命中同一个业务幂等键

## 核心指标

排查幂等冲突时，优先关注以下指标：

- requestCount：请求总数
- duplicateCount：重复请求数量
- conflictCount：冲突请求数量
- acceptedCount：被接受处理的请求数量
- rejectedDuplicateCount：被拒绝的重复请求数量
- hashMismatchCount：同 key 不同请求体的数量
- redisSetNxSuccessCount：SETNX 成功次数
- redisSetNxFailCount：SETNX 失败次数
- redisErrorCount：Redis 异常次数
- avgCheckDurationMs：幂等检查平均耗时

如果 hashMismatchCount 大于 0，说明同一个 Idempotency-Key 被用于不同请求体，属于典型幂等冲突。

## 常见原因

幂等冲突常见原因包括：

1. 客户端重复提交  
   用户多次点击提交按钮，或者前端没有做按钮防重复。

2. 网络超时后重试  
   客户端没有收到响应，自动发起重试。

3. Idempotency-Key 生成不稳定  
   客户端每次重试生成了不同 key，导致服务端无法识别同一业务请求。

4. 同一个 Idempotency-Key 被错误复用  
   不同请求体使用了同一个 key，导致 requestHash 不一致。

5. 服务端只校验 key，没有校验 requestHash  
   只用 key 判断重复，可能误把不同请求当成同一请求。

6. Redis 幂等记录丢失  
   TTL 设置过短或 Redis 异常，可能导致重复请求重新进入业务处理。

7. 缺少数据库唯一索引兜底  
   只依赖 Redis，缺少 DB 层唯一约束，极端情况下可能产生重复数据。

## 排查步骤

1. 检查 Idempotency-Key

确认重复请求是否携带相同 Idempotency-Key。

2. 检查 requestHash

如果相同 key 对应不同 requestHash，说明客户端错误复用了幂等 key。

3. 检查 Redis SETNX 结果

redisSetNxSuccessCount 表示首次处理成功，redisSetNxFailCount 表示命中已有幂等 key。

4. 检查幂等记录状态

幂等记录通常需要区分：

- PROCESSING：正在处理中
- SUCCESS：处理成功，可复用历史结果
- FAILED：处理失败，可根据业务决定是否允许重试

5. 检查数据库唯一索引

确认核心业务表是否有唯一索引兜底，避免 Redis 异常时产生重复数据。

6. 检查异常路径

确认业务异常、超时、事务回滚时，幂等状态是否会正确更新。

## 修复建议

1. 客户端生成稳定 Idempotency-Key

同一次业务请求的重试必须复用相同 Idempotency-Key。

2. 服务端保存 requestHash

服务端不能只保存 key，还应保存请求体 hash，用于识别同 key 不同请求体冲突。

3. 使用 Redis SETNX 做快路径

在请求进入核心业务前，用 SETNX 拦截重复请求。

4. 数据库唯一索引兜底

Redis 只能作为接口层防重复，数据库唯一索引是最终一致性保障。

5. 保存处理状态和响应结果

对于 SUCCESS 状态的重复请求，可以直接返回历史结果。

6. 合理处理 PROCESSING 状态

对于正在处理中的重复请求，可以返回处理中，也可以短暂等待后查询结果。

7. Redis 异常时明确 fail-open / fail-close 策略

高风险写操作通常更适合 fail-close，避免重复写入。低风险查询类操作可以考虑 fail-open。

## 风险提示

- 不要只依赖前端防重复，后端必须做幂等控制。
- 不要只校验 Idempotency-Key，必须结合 requestHash。
- 不要只依赖 Redis，关键业务需要数据库唯一索引兜底。
- 不要把 Redis TTL 设置得过短，否则重试窗口内可能失效。
- 幂等记录状态更新需要和业务事务边界一起设计。