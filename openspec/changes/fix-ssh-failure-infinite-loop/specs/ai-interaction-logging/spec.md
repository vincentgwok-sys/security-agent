## 新增需求

### 需求: SSH 连接失败导致的检测终止必须记录日志

当 Skill 检测因 SSH 连接不可达而终止时，系统必须在 AI 日志中记录一条明确的失败标记，区分于常规的命令执行错误。

#### 场景: 连接失败终止时记录日志

- **当** `DetectionOrchestrator` 因 `ExecutionResult.connectionError == true` 而终止 Skill 检测
- **那么** 系统必须创建一条 `AiLogEntry`，`phase` 设为 `"ssh-connection-error"`，`rawResponse` 包含连接异常的详细错误消息，`parseResult` 设为 `"FAILED"`

#### 场景: 前端日志查看器展示连接错误

- **当** 用户查看日志卡片列表
- **那么** `phase` 为 `"ssh-connection-error"` 的日志卡片必须以红色标签醒目展示，与正常阶段的颜色区分
