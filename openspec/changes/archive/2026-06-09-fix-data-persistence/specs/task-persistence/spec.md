## 新增需求

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
