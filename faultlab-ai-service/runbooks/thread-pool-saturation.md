---
docId: thread-pool-saturation
title: 线程池饱和排查手册
faultType: THREAD_POOL_SATURATION
keywords: thread pool, executor, saturation, taskCount, acceptedTaskCount, rejectedTaskCount, activeThreadCount, queueSize, queueCapacity, avgTaskDurationMs, taskSleepMs, poolSize, maximumPoolSize
---

# 线程池饱和排查手册

## 现象

线程池饱和通常表现为任务提交速度超过线程池处理能力，导致工作线程被占满、队列持续堆积，严重时触发拒绝策略。

在故障演练场景中，常见表现包括：

- activeThreadCount 接近或达到最大线程数
- poolSize 接近 maximumPoolSize
- queueSize 持续大于 0 或接近 queueCapacity
- rejectedTaskCount 大于 0
- avgTaskDurationMs 较高
- taskSleepMs 较大
- 接口响应变慢
- 异步任务执行延迟增加

## 核心指标

排查线程池饱和时，优先关注以下指标：

- taskCount：提交任务数量
- acceptedTaskCount：成功提交任务数量
- rejectedTaskCount：被拒绝任务数量
- activeThreadCount：活跃线程数
- queueSize：队列长度
- queueCapacity：队列容量
- avgQueueWaitMs：队列等待耗时
- avgTaskDurationMs：平均任务执行耗时
- taskSleepMs：模拟或实际任务耗时
- poolSize：当前线程池线程数
- corePoolSize：核心线程数
- maximumPoolSize：最大线程数
- rejectedExecutionHandler：拒绝策略

如果 activeThreadCount 已经打满，同时 queueSize 增长或 rejectedTaskCount 大于 0，基本可以判断线程池出现饱和风险。

## 常见原因

线程池饱和常见原因包括：

1. 单个任务执行时间过长
   例如任务内部有慢 SQL、远程接口超时、文件 IO、阻塞 IO、锁等待或复杂计算。相关字段包括 slow task、avgTaskDurationMs、taskSleepMs、blockingIoCount、remoteCallTimeoutCount。

2. 任务提交速度过快
   上游请求量突然升高，超过线程池处理能力。相关字段包括 taskCount、acceptedTaskCount、queueSize。

3. 核心线程数和最大线程数设置过小
   参数配置不足以支撑当前业务并发。相关字段包括 corePoolSize、maximumPoolSize、poolSize、activeThreadCount。

4. 队列容量设置不合理
   队列过小容易快速拒绝任务，队列过大可能导致延迟堆积和内存压力。相关字段包括 queueSize、queueCapacity、avgQueueWaitMs。

5. 拒绝策略不合理
   直接抛异常可能影响用户请求，静默丢弃又可能造成数据丢失。相关字段包括 rejectedTaskCount、rejection policy、rejectedExecutionHandler。

6. 线程池被多个业务复用
   一个慢业务占满线程池后，其他业务也会被拖慢。相关字段包括 sharedExecutor、thread pool isolation、criticalTaskDelayMs、slowBusinessTaskCount。

## 排查步骤

1. 查看活跃线程数

如果 activeThreadCount 接近 maximumPoolSize，说明线程资源已经紧张。需要结合 poolSize、corePoolSize、maximumPoolSize 一起判断。

2. 查看队列长度

如果 queueSize 持续增长，说明任务处理速度低于任务提交速度。queueSize 接近 queueCapacity 时，需要评估队列容量和排队延迟。

3. 查看拒绝任务数

如果 rejectedTaskCount 大于 0，说明线程池已经无法继续接收任务。需要检查 rejectedExecutionHandler 和 rejection policy。

4. 分析任务耗时

如果 avgTaskDurationMs 或 taskSleepMs 较高，需要排查任务内部是否存在慢 SQL、远程调用、阻塞 IO、锁等待或 blocking dependency。

5. 检查线程池参数

重点确认：

- corePoolSize
- maximumPoolSize
- queueCapacity
- keepAliveTime
- rejectedExecutionHandler

6. 检查业务隔离

确认是否多个不同业务共用同一个线程池。如果共用线程池，需要考虑拆分隔离，避免 slow task 影响 critical task。

## 修复建议

1. 优化慢任务

优先优化任务内部慢 SQL、远程调用超时、阻塞 IO 等问题。相关字段包括 slow task、avgTaskDurationMs、blocking dependency、remoteCallTimeoutCount。

2. 合理调整线程池参数

根据业务类型调整 corePoolSize、maximumPoolSize 和 queueCapacity。调整前需要结合 CPU、IO 和下游服务承载能力评估。

3. 对任务进行限流

避免瞬时大量任务直接打满线程池。对于上游突增流量，可以增加限流、削峰或排队保护。

4. 拆分业务线程池

不同业务使用不同线程池，避免互相影响。相关字段包括 thread pool isolation、sharedExecutor、criticalTaskDelayMs。

5. 增加拒绝策略监控

拒绝任务不能只记录日志，需要接入监控和告警。相关字段包括 rejectedTaskCount、rejection policy、rejectedExecutionHandler。

6. 对长任务做拆分

将长耗时任务拆成更小的子任务，降低单个任务占用线程时间。

## 风险提示

- 不要盲目增大线程数，线程过多会带来上下文切换和内存压力。
- 不要把队列设置得无限大，否则可能造成请求延迟持续堆积。相关字段包括 queueSize、queueCapacity、avgQueueWaitMs。
- 不要忽略 rejectedTaskCount，它通常说明系统已经进入过载状态。
- 线程池参数调整前，需要结合 CPU、IO、下游服务承载能力一起评估。
- 拒绝策略如果配置不当，可能导致用户请求失败或数据丢失。
- 共用线程池缺少 thread pool isolation 时，慢业务可能拖垮核心业务。
