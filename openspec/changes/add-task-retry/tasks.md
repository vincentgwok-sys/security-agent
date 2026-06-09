## 1. 后端模型扩展

- [x] 1.1 在 `DetectionTask.java` 中新增 `String parentTaskId` 字段
- [x] 1.2 在 `TaskController.CreateTaskRequest` 中新增可选 `String parentTaskId` 字段
- [x] 1.3 在 `TaskManagementService.createTask()` 中接收并设置 `parentTaskId`

## 2. 前端重试按钮

- [x] 2.1 在 `api/index.js` 中导出 `getTask(taskId)`（已存在，确认可用）
- [x] 2.2 在 `TaskList.vue` 中为 `INTERRUPTED` 和 `FAILED` 任务新增"重试"按钮
- [x] 2.3 实现重试逻辑：调用 `getTask()` 获取原任务 → 调用 `createTask()` 创建新任务（传递 parentTaskId）→ 刷新列表

## 3. 验证

- [x] 3.1 对一个 INTERRUPTED 任务点击重试，确认新任务创建成功并自动开始检测
- [x] 3.2 确认 COMPLETED 和 RUNNING 任务不显示重试按钮
- [x] 3.3 确认新任务的 `.task.json` 文件中包含 `parentTaskId` 字段
