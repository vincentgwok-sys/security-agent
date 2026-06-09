## 为什么

状态为 `INTERRUPTED`（服务重启中断）或 `FAILED` 的任务无法重新执行。用户需要手动复制 IP、端口、密码和 Skill 列表，回到创建任务页面重新填写。这不仅繁琐，且容易因密码遗忘而无法重试。

## 变更内容

- 任务列表页为 `INTERRUPTED` 和 `FAILED` 任务新增"重试"按钮
- 点击重试后，前端调用 `POST /api/tasks` 复用原任务的连接信息和 Skill 列表创建新任务
- 新建任务记录父任务 ID（`parentTaskId`），便于追溯重试链

## 功能 (Capabilities)

### 新增功能

- `task-retry`: 用户可从任务列表对失败/中断的任务一键重试，使用最新的 Skill 和规则重新检测

### 修改功能

无

## 影响

| 范围 | 详情 |
|---|---|
| 代码 | `DetectionTask.java`（新增 parentTaskId）、`TaskController.java`（支持 parentTaskId）、`TaskList.vue`（新增重试按钮） |
| API | `POST /api/tasks` 新增可选字段 `parentTaskId` |
| 规范 | 新增 `task-retry` 能力 |
| 破坏性 | 无 |
