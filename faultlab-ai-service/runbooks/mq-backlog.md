---
docId: mq-backlog
title: MQ 消息堆积排查手册
faultType: MQ_BACKLOG
keywords: RabbitMQ, MQ, message backlog, publishCount, consumeCount, backlogCount, avgConsumeMs, consumerDelayMs, queueReadyCount, queueUnackedCount, ready messages, unacked messages, queue
---

# MQ 消息堆积排查手册

## 现象

MQ 消息堆积通常表现为生产端持续写入消息，但消费端处理速度跟不上，导致队列中的未消费消息数量不断增加。

在故障演练场景中，常见表现包括：

- publishCount 明显大于 consumeCount
- backlogCount 持续大于 0
- avgConsumeMs 明显升高
- consumerDelayMs 较大
- 队列中 Ready 或 Unacked 消息数量增加
- queueReadyCount、queueUnackedCount 持续增长
- 消费端处理延迟变长

## 核心指标

排查 MQ 消息堆积时，优先关注以下指标：

- publishCount：生产消息数量
- consumeCount：消费消息数量
- backlogCount：生产数量与消费数量的差值，常用于表示 message backlog
- avgConsumeMs：平均消费耗时，也可理解为 consume latency
- consumerDelayMs：模拟或实际消费延迟，也可理解为 consumer delay
- queueReadyCount：队列中等待消费的消息数，对应 ready messages
- queueUnackedCount：已投递但未确认的消息数，对应 unacked messages
- consumerCount：消费者实例数量
- consumerThreadCount：消费者并发线程数
- publishRate：生产速率
- ackRate：确认速率

如果 publishCount > consumeCount，且 avgConsumeMs 或 consumerDelayMs 较高，通常可以判断消费端处理能力不足。

## 常见原因

MQ 消息堆积常见原因包括：

1. 消费端处理逻辑耗时过长
   例如消费端内部存在慢 SQL、远程接口调用超时、文件处理、复杂计算等。相关字段包括 slow consumer、avgConsumeMs、consumerDelayMs、slowSqlCount。

2. 消费者并发不足
   消费者数量或并发线程数过低，无法跟上生产速度。相关字段包括 consumer concurrency、consumerCount、consumerThreadCount、publishRate、ackRate。

3. 下游依赖变慢
   消费端依赖数据库、缓存、第三方服务等，如果下游响应变慢，会直接拖慢消费速度。相关字段包括 downstream slow、dbLatencyMs、redisLatencyMs、remote timeout。

4. 消费失败后重复重试
   如果消息一直消费失败并被重新投递，可能导致队列处理效率下降。相关字段包括 retry、retryCount、redeliverCount、consumeErrorCount。

5. 消息体过大
   单条消息过大时，序列化、反序列化和网络传输成本都会上升。

6. 消费端 ACK 时机不合理
   如果消费逻辑执行完很久才 ACK，可能导致 unacked messages 长时间积压。相关字段包括 ACK、ackRate、queueUnackedCount。

## 排查步骤

1. 先确认生产速度和消费速度

检查 publishCount、consumeCount、publishRate、ackRate 的差值。如果生产速度远高于消费速度，需要继续确认是消费端慢还是消费者数量不足。

2. 查看消费耗时

如果 avgConsumeMs 或 consumerDelayMs 较高，说明单条消息处理时间较长。需要重点排查消费方法内部逻辑。

3. 检查消费端日志

重点查看是否存在：

- 慢 SQL
- 远程调用超时
- 反序列化异常
- 业务异常重复重试
- 消费线程阻塞

4. 查看 RabbitMQ 队列状态

重点关注：

- Ready 消息数量，对应 queueReadyCount、ready messages
- Unacked 消息数量，对应 queueUnackedCount、unacked messages
- Consumer 数量
- Message rates
- Deliver / Ack 速率

5. 检查下游服务

如果消费端依赖数据库、Redis、外部服务，需要确认这些依赖是否变慢。相关字段包括 dbLatencyMs、redisLatencyMs、downstream slow。

6. 检查重试和死信策略

如果失败消息反复重试，应确认是否需要进入死信队列，而不是无限重试。相关字段包括 retryCount、redeliverCount、consumeErrorCount、deadLetterCount、dead letter queue、DLQ。

## 修复建议

1. 增加消费者并发

可以增加消费者实例数量，或者提高单个实例的消费并发度。对应字段包括 consumer concurrency、consumerCount、consumerThreadCount。

2. 优化消费端慢逻辑

如果消费端内部有慢 SQL、远程调用或阻塞 IO，应优先优化这些耗时点。对应字段包括 slow consumer、avgConsumeMs、consumerDelayMs、dbLatencyMs、redisLatencyMs。

3. 对长耗时任务做异步拆分

消费逻辑中不要放过重的同步处理，可以将长任务拆分为异步子任务。

4. 增加限流和削峰

如果生产速度过高，可以在生产端增加限流，或者使用缓冲层削峰。

5. 增加死信队列

对于多次消费失败的消息，应进入死信队列，避免阻塞正常消息。对应字段包括 dead letter、dead letter queue、DLQ、deadLetterCount、retryCount。

6. 保证消费幂等

重复投递和重试是 MQ 场景中常见情况，消费端必须具备幂等能力。

## 风险提示

- 不要只依靠增加消费者解决所有问题，如果消费逻辑本身很慢，扩容只能暂时缓解。
- 不要无限重试失败消息，否则可能造成队列持续堆积。相关字段包括 retryCount、redeliverCount。
- 不要在消费线程中执行不可控的长时间阻塞操作。
- 提升并发前需要确认数据库和下游服务能承受更高压力。
- 如果 deadLetterCount 一直为 0 且 retryCount 持续上升，需要检查 DLQ 配置是否缺失。
- queueUnackedCount 或 unacked messages 持续增长时，应重点检查 ACK 时机和消费线程阻塞。
