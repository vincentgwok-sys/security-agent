## 为什么

运行中的任务无法终止，已结束的任务无法从列表中删除。导致服务重启前 RUNNING 任务一直占据界面，INTERRUPTED/FAILED 任务堆积。

## 变更内容

- **终止**：新增 `POST /api/tasks/{taskId}/cancel`，取消 CompletableFuture、设置状态为 INTERRUPTED、关闭 SSH 会话
- **删除**：新增 `DELETE /api/tasks/{taskId}`，仅删除 .task.json 和内存记录，保留日志和报告
- **前端**：RUNNING 任务显示"终止"按钮，终态任务显示"删除"按钮

## 功能

### 新增功能

- `task-cancel`: 终止运行中的检测任务
- `task-delete`: 从任务列表删除非运行中任务

## 影响

`TaskManagementService`, `TaskController`, `DetectionOrchestrator`, `SshExecutionService`, `TaskList.vue`, `api/index.js`
