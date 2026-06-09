## 上下文

`DetectionOrchestrator` 通过 `CompletableFuture.runAsync()` 异步执行检测。JVM 关闭时这些异步任务直接终止，但 `TaskManagementService` 仅在内存中维护状态——持久化的 `.task.json` 文件中的 `status` 字段停留在 `RUNNING`。

当前 `TaskStatus` 枚举：`CREATED`, `RUNNING`, `COMPLETED`, `FAILED`。

## 目标 / 非目标

**目标：**
- 重启后将 `RUNNING` 和 `CREATED` 任务标记为 `INTERRUPTED`
- 前端正确展示新状态

**非目标：**
- 不实现任务恢复/续跑机制
- 不改变任务执行流程

## 决策

### 决策 1：新增 `INTERRUPTED` 枚举值

在 `TaskStatus` 中增加 `INTERRUPTED`，语义明确区别于 `FAILED`（任务自身错误）和 `CREATED`（尚未执行）。

### 决策 2：在 `init()` 中遍历并更新状态

`init()` 加载任务后遍历 `taskStore`，对 `status == RUNNING || status == CREATED` 的任务：
1. 设置 `status = INTERRUPTED`
2. 设置 `errorMessage = "服务重启，检测中断"`
3. 调用 `persistTask()` 写回磁盘

**替代方案**：仅在内存中标记不持久化 → 下次重启仍要处理，不选。

## 风险 / 权衡

- 无。改动局限在启动阶段，不涉及运行时路径。
