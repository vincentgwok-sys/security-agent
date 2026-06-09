## 1. 后端 — 任务生命周期管理

- [x] 1.1 `TaskManagementService` 新增 `runningFutures` 映射、`registerRunningTask()`、`cancelTask()`、`deleteTask()` 方法
- [x] 1.2 `TaskController` 构造函数注入 `SshExecutionService`，`createTask` 中注册 future
- [x] 1.3 `TaskController` 新增 `POST /{taskId}/cancel` 端点（终止 + 释放 SSH 会话）
- [x] 1.4 `TaskController` 新增 `DELETE /{taskId}` 端点（仅删除 .task.json，保留日志和报告）
- [x] 1.5 `DetectionOrchestrator` 命令循环中增加 `Thread.isInterrupted()` 检查

## 2. 前端

- [x] 2.1 `api/index.js` 新增 `cancelTask()` 和 `deleteTask()` API 调用
- [x] 2.2 `TaskList.vue` 新增终止按钮（RUNNING/CREATED）和删除按钮（COMPLETED/INTERRUPTED/FAILED）

## 3. 验证

- [ ] 3.1 对一个 RUNNING 任务点击终止，确认状态变为 INTERRUPTED 且 SSH 会话关闭
- [ ] 3.2 对一个 INTERRUPTED 任务点击删除，确认从列表消失但日志仍可查看
