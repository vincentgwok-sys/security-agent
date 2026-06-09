## 目的

任务持久化将任务数据从 JVM 内存迁移到本地 JSON 文件存储，确保服务重启后历史数据不丢失。

## 需求

### 需求: 任务 JSON 文件持久化

系统必须在任务生命周期变更时将 `DetectionTask` 实体序列化为 `reports/{taskId}.task.json` 文件存储，服务重启后自动回读重建内存索引。

#### 场景: 创建任务时持久化

- **当** 系统调用 `TaskManagementService.createTask()` 创建新任务
- **那么** 系统生成 taskId 后将完整 `DetectionTask` JSON 写入 `reports/{taskId}.task.json`，createdAt 和 updatedAt 均设置为当前时间

#### 场景: 状态变更时更新持久化

- **当** 系统调用 `updateStatus()` 修改任务状态
- **那么** 系统更新内存中的 `DetectionTask` 对象后，立即覆写对应的 `reports/{taskId}.task.json` 文件，updatedAt 更新为当前时间

#### 场景: 服务启动时回读历史任务

- **当** Spring Boot 应用启动完成（`@PostConstruct` 或 `ApplicationRunner`）
- **那么** 系统扫描 `reports/` 目录下所有 `*.task.json` 文件，反序列化为 `DetectionTask` 对象，批量加载到内存 `ConcurrentHashMap` 中

#### 场景: 启动时 reports 目录不存在

- **当** `reports/` 目录尚不存在
- **那么** 系统自动创建目录，记录 INFO 日志，返回空任务列表（不抛异常）

#### 场景: 持久化文件写入失败

- **当** 磁盘写入异常（如权限不足、磁盘满）
- **那么** 系统记录 ERROR 日志（含 taskId 和异常堆栈），但不中断业务流程——内存中的任务状态仍然有效

#### 场景: 回读时文件格式损坏

- **当** 某个 `*.task.json` 文件 JSON 无法反序列化
- **那么** 系统记录 ERROR 日志（含文件名和错误原因），跳过该文件继续加载其余合法文件

### 需求: 持久化路径使用基于 user.dir 的绝对路径

系统必须将 `reports/`、`skills/`、`rules/` 三个目录的配置路径解析为基于 `user.dir` 系统属性的绝对路径，而非 JVM 启动时的相对路径。

#### 场景: 配置相对路径被解析为绝对路径

- **当** `application.yml` 中配置 `security-agent.report.output-directory: ./reports`
- **那么** 系统在运行时将其解析为 `{user.dir}/reports` 绝对路径，确保无论 JVM 从哪个目录启动都能正确定位

#### 场景: 配置已包含绝对路径则保持不变

- **当** `application.yml` 中配置 `security-agent.report.output-directory: /data/agent/reports`
- **那么** 系统保持该绝对路径不变，不拼接 `user.dir`

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
