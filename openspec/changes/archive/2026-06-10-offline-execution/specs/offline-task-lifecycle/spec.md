## 新增需求

### 需求: 线下任务连接类型

系统必须支持第三种连接类型 `connectionType: "offline"`，与现有 `"ssh"` 和 `"kubectl"` 并列。

#### 场景: 任务实体包含 connectionType

- **当** 创建 `connectionType` 为 `"offline"` 的任务
- **那么** `DetectionTask.connectionType` 字段必须为 `"offline"`，持久化到 `{taskId}.task.json`，服务重启后正确恢复

#### 场景: 线下任务跳过 SSH 连接

- **当** `DetectionOrchestrator` 检查任务 `connectionType`
- **那么** 对于 `"offline"` 类型，不尝试建立 SSH 连接，不调用 `SshExecutionService`

#### 场景: 默认连接类型

- **当** 创建任务请求未指定 `connectionType` 或为 `null`
- **那么** 系统默认使用 `"ssh"`

### 需求: 线下任务状态机

线下任务必须遵循独立的状态流转：CREATED → SCRIPT_DOWNLOADED → RESULTS_UPLOADED → ANALYZING → COMPLETED/FAILED。

#### 场景: 创建后状态为 CREATED

- **当** 线下任务创建成功
- **那么** 任务状态为 `CREATED`，脚本可供下载

#### 场景: 首次下载后状态变更为 SCRIPT_DOWNLOADED

- **当** 用户首次成功下载脚本
- **那么** 系统将任务状态变更为 `SCRIPT_DOWNLOADED`，更新 `updatedAt`，持久化状态

#### 场景: 上传结果后状态变更为 RESULTS_UPLOADED

- **当** 用户成功上传有效的 ZIP 结果包
- **那么** 系统在 ZIP 校验通过后将任务状态变更为 `RESULTS_UPLOADED`，然后立即变更为 `ANALYZING`（回放启动）

#### 场景: 回放进行中状态为 ANALYZING

- **当** AI 回放分析异步运行中
- **那么** 任务状态为 `ANALYZING`

#### 场景: 回放完成后状态为 COMPLETED

- **当** 回放成功完成，报告已生成
- **那么** 任务状态为 `COMPLETED`

#### 场景: 回放失败后状态为 FAILED

- **当** 回放过程中发生不可恢复错误
- **那么** 任务状态为 `FAILED`，`errorMessage` 记录错误原因

#### 场景: 服务重启时 ANALYZING 任务标记为 INTERRUPTED

- **当** 服务在回放过程中重启
- **那么** 启动恢复时将 `ANALYZING` 状态的任务自动标记为 `INTERRUPTED`

### 需求: TaskStatus 枚举扩展

`TaskStatus` 枚举必须新增 `SCRIPT_DOWNLOADED`、`RESULTS_UPLOADED`、`ANALYZING` 三个状态值。

#### 场景: 所有新状态可序列化

- **当** 新状态值被 Jackson 序列化为 JSON
- **那么** 输出文件中的 `status` 字段分别为 `"SCRIPT_DOWNLOADED"`、`"RESULTS_UPLOADED"`、`"ANALYZING"`

#### 场景: 旧状态文件兼容

- **当** 服务升级后读取旧版本持久化的任务文件（仅含 5 个原有状态）
- **那么** Jackson 反序列化正常，不会因新增枚举值而失败

### 需求: 线下任务不支持重试

`connectionType` 为 `"offline"` 的任务禁止发起重试。

#### 场景: 前端隐藏重试按钮

- **当** 任务列表中某任务的 `connectionType` 为 `"offline"`
- **那么** 操作列不显示"重试"按钮，无论任务状态如何

#### 场景: API 拒绝重试

- **当** 尝试为 `connectionType: "offline"` 的任务创建重试（`POST /api/tasks` 含 `parentTaskId`）
- **那么** 系统返回 HTTP 400，错误消息"线下任务不支持重试，请重新创建任务"

### 需求: 线下任务状态查询

线下任务的状态信息必须在任务详情 API 中包含与线下执行相关的扩展字段。

#### 场景: 任务详情包含线下字段

- **当** 请求 `GET /api/tasks/{taskId}`，任务 `connectionType` 为 `"offline"`
- **那么** 响应 JSON 包含：`connectionType: "offline"`、`scriptDownloadedAt`（如已下载）、`resultUploadedAt`（如已上传）

#### 场景: 在线任务不包含线下字段

- **当** 请求 `GET /api/tasks/{taskId}`，任务 `connectionType` 为 `"ssh"`
- **那么** 响应 JSON 中的 `scriptDownloadedAt` 和 `resultUploadedAt` 字段为 `null` 或不存在
