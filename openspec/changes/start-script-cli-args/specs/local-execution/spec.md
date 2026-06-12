## 新增需求

### 需求:系统必须支持本地执行模式

创建检测任务时，`connectionType` 字段必须支持 `"local"` 值。该模式下，检测命令通过 `ProcessBuilder` 在本机执行，不使用 SSH。

#### 场景:创建本地执行任务
- **当** 用户通过 `POST /api/tasks` 创建任务，请求体 `connectionType` 为 `"local"`
- **而且** 不传 `targetIp`、`sshUser`、`sshPassword`
- **那么** 系统必须接受请求，创建任务并启动异步检测（本地执行）

#### 场景:本地任务不需要 SSH 凭证
- **当** 用户创建 `connectionType=local` 的任务
- **而且** 请求体中未包含 `targetIp`
- **那么** 系统必须正常创建任务，不得返回校验错误

### 需求:本地命令执行必须使用 ProcessBuilder

本地模式的所有命令必须通过 `sh -c "<command>"` 在本机执行。ProcessBuilder 必须设置超时来防止命令挂起。

#### 场景:本地执行检测命令
- **当** `DetectionOrchestrator` 检测到任务 `connectionType` 为 `"local"`
- **而且** 需要执行检测命令 `cat /proc/1/status`
- **那么** `LocalExecutionService` 必须使用 `ProcessBuilder("sh", "-c", "cat /proc/1/status")` 执行
- **而且** 返回的 `ExecutionResult` 必须包含 stdout、stderr、exitCode

#### 场景:本地命令超时
- **当** 本地执行的命令运行超过配置的超时时间（默认 30 秒）
- **那么** 系统必须终止子进程
- **而且** 返回的 `ExecutionResult` 中 exitCode 为 -1 且标记超时

### 需求:本地模式下环境指纹采集必须适配

Phase 0 的环境指纹采集（`collectEnvironmentFingerprint()`）在本地模式下必须直接在本机执行探测命令。

#### 场景:本地指纹采集
- **当** 任务 `connectionType` 为 `"local"`
- **而且** 需要采集环境指纹（检查 OS 类型、包管理器、可用命令等）
- **那么** 指纹采集命令必须通过 `LocalExecutionService` 执行
- **而且** 返回的 `EnvironmentFingerprint` 必须正确标注 `connectionType=local`

### 需求:本地模式与 SSH 模式结果格式一致

本地执行的结果格式必须与 SSH 执行完全一致，确保报告生成和 AI 判定逻辑无需感知执行方式。

#### 场景:结果格式兼容
- **当** 本地执行完成所有检测命令
- **那么** 每个命令的执行结果必须是标准的 `ExecutionResult` 对象
- **而且** AI 判定流程（Phase 1）和报告生成（Phase 2）逻辑不变
