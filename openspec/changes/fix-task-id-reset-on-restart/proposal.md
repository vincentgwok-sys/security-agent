## 为什么

每次重启 Spring Boot 服务后，新建任务的 ID 序号都会从 1 开始（如 `TASK-20260609-0001`），导致覆盖磁盘上同名已有任务的 `.task.json`、日志文件、报告文件等。根因是 `TaskManagementService.init()` 在从磁盘恢复今日任务计数时，只设置了 `dailySeq`，未同步更新 `currentDate`，导致 `generateTaskId()` 首次调用时将计数器错误重置为 0。

## 变更内容

- 修复 `TaskManagementService.init()`：在恢复今日任务计数时同步设置 `currentDate`，使 `generateTaskId()` 不会错误重置序号
- 在 `task-persistence` 规范中新增重启后序号连续性的需求场景

## 功能 (Capabilities)

### 新增功能

无

### 修改功能

- `task-persistence`: 新增需求——服务重启后任务 ID 序号必须从磁盘已有最大值之后开始，保证不覆盖已有任务

## 影响

| 范围 | 详情 |
|---|---|
| 代码 | `TaskManagementService.java` — `init()` 方法，约 1 行改动 |
| 规范 | `openspec/specs/task-persistence/spec.md` — 新增重启连续性场景 |
| 配置 | 无 |
| API | 无变更 |
| 破坏性 | 无 |
