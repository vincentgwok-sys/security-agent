## 新增需求

### 需求: SSH 连接失败时立即终止 Skill 检测

系统在 Skill 检测执行过程中，当检测到 SSH 连接不可达（非命令执行失败）时，必须立即终止当前 Skill 的检测流程并返回失败报告，禁止继续触发上下文进化或无限重试。

#### 场景: SSH 连接异常终止检测

- **当** `SshExecutionService.execute()` 或 `executeRaw()` 捕获到连接级异常（如 `UnresolvedAddressException`、`ConnectException`）
- **那么** 返回的 `ExecutionResult.connectionError` 必须为 `true`
- **且** `DetectionOrchestrator` 在执行命令后检查 `result.isConnectionError()`，若为 `true` 则立即终止当前 Skill 检测并返回失败报告

#### 场景: ENV_MISMATCH 时优先检查连接状态

- **当** AI 判定返回 `ENV_MISMATCH`
- **那么** `DetectionOrchestrator` 必须先检查 `result.isConnectionError()`
- **且** 若为连接错误，必须直接返回 `buildConnectionFailedReport()`，禁止进入 `evolveNewContext()` 上下文进化流程

#### 场景: 命令执行失败不标记连接错误

- **当** SSH 连接正常但命令执行超时或被目标 Shell 拒绝
- **那么** `ExecutionResult.connectionError` 必须为 `false`
- **且** 系统正常进入 EVOLVE/ENV_MISMATCH 处理流程

### 需求: 上下文进化次数硬上限

系统必须限制每次 Skill 检测中上下文进化的最大触发次数，超出上限后必须终止检测并返回失败报告。

#### 场景: 上下文进化达上限后终止

- **当** 当前 Skill 检测中上下文进化次数已达到 `security-agent.detection.max-context-evolutions`（默认 3）
- **那么** 再次触发 `ENV_MISMATCH` 时系统必须终止检测，记录 WARN 日志并返回失败报告，禁止继续进化

#### 场景: 正常检测不受上限影响

- **当** 当前 Skill 检测中上下文进化次数未达上限且有合法的环境变化需要进化
- **那么** 系统正常执行上下文进化流程
