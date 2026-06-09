## 新增需求

### 需求: 服务重启后任务 ID 序号必须连续

系统在服务启动恢复任务存储时，必须同时恢复当日任务 ID 序号状态，确保重启后首个新任务 ID 的序号不从 1 开始，从而避免覆盖磁盘上已存在的任务及其关联数据。

#### 场景: 重启后首个任务 ID 序号接续已有最大值

- **当** 服务重启且 `reports/` 目录下已存在 `n` 个当日任务文件（如 `TASK-20260609-0001.task.json` 到 `TASK-20260609-0003.task.json`）
- **那么** `TaskManagementService.init()` 必须将 `dailySeq` 设置为 `n`，同时将 `currentDate` 设置为当日日期字符串
- **且** 随后调用 `generateTaskId()` 生成的第一个任务 ID 序号必须是 `n + 1`（如上例中应为 `TASK-20260609-0004`）

#### 场景: 重启时当日无历史任务则从 1 开始

- **当** 服务重启且 `reports/` 目录下不存在当日任务文件
- **那么** `dailySeq` 恢复为 0
- **且** 随后 `generateTaskId()` 生成的第一个 ID 序号为 1（如 `TASK-20260609-0001`）

#### 场景: currentDate 必须与 dailySeq 同步恢复

- **当** `init()` 设置 `dailySeq` 的值时
- **那么** `init()` 必须同步设置 `currentDate` 为当日日期，禁止仅设置 `dailySeq` 而遗留 `currentDate` 为空字符串
