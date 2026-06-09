## 上下文

`TaskController.createTask()` 通过 `POST /api/tasks` 创建新任务并异步启动检测。该端点接收 `CreateTaskRequest`（含 targetIp、sshUser、sshPassword、sshPort、skillIds）。检测会重新从磁盘加载 Skill（`loadLatestSkills()`），确保使用最新版本。

`DetectionTask` 已包含完整的 SSH 连接信息，`GET /api/tasks/{taskId}` 返回完整对象（含密码），前端可直接复用。

## 目标 / 非目标

**目标：**
- 任务列表中对 `INTERRUPTED` 和 `FAILED` 任务显示"重试"按钮
- 一键创建新任务并自动启动检测
- 通过 `parentTaskId` 记录重试关系

**非目标：**
- 不实现原地重跑（复用原 taskId）
- 不支持 `COMPLETED` 任务的重试

## 决策

### 决策 1：复用 POST /api/tasks，不新增端点

前端从原任务拉取完整数据后调用 `createTask()`，后端生成新 taskId。不新增 `/api/tasks/{taskId}/retry` 端点，避免后端多余逻辑。

### 决策 2：新增 parentTaskId 字段

`DetectionTask` 新增可选 `String parentTaskId`。`CreateTaskRequest` 新增可选 `String parentTaskId`。创建新任务时透传。前端可在任务详情页展示重试链。

### 决策 3：重试按钮仅对 INTERRUPTED 和 FAILED 显示

`COMPLETED` 和 `RUNNING` 不显示重试按钮。`CREATED` 理论上也不需要（尚未执行）。

## 风险 / 权衡

- 密码暴露：`GET /api/tasks/{taskId}` 返回明文密码 → 现有行为，非本次引入。如需修复应另建变更。
