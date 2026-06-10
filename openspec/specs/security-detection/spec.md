## 目的

安全检测能力是系统的核心流程——通过 SSH 连接池访问目标容器，执行环境探针命令采集指纹，按匹配规则选择最佳 executionContext，经规则引擎过滤后逐条执行检测命令并提交 AI 判定。

## 需求

### 需求: 创建检测任务

系统必须提供 `POST /api/tasks` 端点创建检测任务。请求体包含目标容器 SSH 连接信息和选中的 Skill ID 列表。

#### 场景: 成功创建任务

- **当** 收到包含 targetIp、sshUser、sshPassword、sshPort、skillIds 的有效请求
- **那么** 系统创建 DetectionTask 实体（状态为 CREATED），生成唯一 taskId，异步启动检测流程，返回 202 Accepted（含 taskId）

#### 场景: 必填字段缺失

- **当** 请求体缺少 targetIp 或 skillIds
- **那么** 系统返回 400 Bad Request，错误消息指明缺失字段

#### 场景: 查询任务列表

- **当** 收到 `GET /api/tasks?page=0&size=20`
- **那么** 系统返回分页的任务列表，每条包含 taskId、targetIp、status、创建时间

#### 场景: 查询任务详情

- **当** 收到 `GET /api/tasks/{taskId}`
- **那么** 系统返回任务的完整详情，包括当前进度、已完成的 Skill 检测状态

### 需求: 环境指纹采集

在执行任何检测命令之前，系统必须收集目标容器的环境指纹信息。采集方式为：遍历 Skill 所有 executionContext 的 `envCheckCommands` 去重后，通过 SSH 批量执行。

#### 场景: 采集环境指纹成功

- **当** 目标容器可正常 SSH 连接
- **那么** 系统执行合并后的探针命令（如 `uname -s`、`cat /etc/os-release`、`which cat grep mount` 等），从输出中解析出 osType、osFlavor、kernelVersion、shellType、arch、availableTools 六维信息，构造 EnvironmentFingerprint 对象

#### 场景: SSH 连接失败

- **当** 目标容器 SSH 连接超时或凭据错误
- **那么** 系统将 Task 标记为 FAILED，记录错误原因，不继续后续检测

### 需求: AI 上下文匹配

环境指纹采集后，系统必须首先尝试程序侧匹配选择 executionContext。若程序侧无匹配，则调用 AI（阶段零 Prompt）辅助选择或建议全新 Context。

#### 场景: 程序侧精确匹配成功

- **当** 存在精确匹配的 executionContext
- **那么** 系统直接使用该 context，不调用 AI 阶段零 Prompt

#### 场景: AI 辅助匹配

- **当** 程序侧仅找到模糊匹配或无匹配，且配置允许 AI 辅助匹配
- **那么** 系统调用阶段零 AI Prompt，传入 Skill 的所有 executionContext 和目标环境指纹，由 AI 返回 matchResult 和 selectedContextId

### 需求: 逐命令执行与 AI 判定

选定 executionContext 后，系统必须按 detectionCommands 顺序逐条执行。每条命令执行前经规则引擎过滤，执行后将结果提交 AI（阶段一 Prompt）做判定。

#### 场景: 命令返回 PASS

- **当** 命令执行回显表明容器防御生效（如 `Operation not permitted`）
- **那么** AI 返回 `{status: "PASS"}`，系统记录结果后继续下一条命令

#### 场景: 命令返回 FAIL

- **当** 命令执行回显表明探测成功（如成功打印特权掩码）
- **那么** AI 返回 `{status: "FAIL", evidence: "CapEff: 0000003fffffffff"}`，系统记录证据后继续下一条命令，最终状态标记为 FAIL

#### 场景: 命令返回 EVOLVE

- **当** 命令在目标环境中不可用（如 `bash: capsh: command not found`）
- **那么** AI 返回 `{status: "EVOLVE", nextCommand: "cat /proc/1/status | grep CapEff"}`，系统使用 nextCommand 重新执行判定

#### 场景: 命令返回 ENV_MISMATCH

- **当** 命令执行结果证明当前 Context 的环境假设根本错误（如预期 /proc 但不存在）
- **那么** AI 返回 `{status: "ENV_MISMATCH"}`，系统触发上下文级进化

### 需求: 任务状态机

检测任务必须遵循以下状态转换：CREATED → RUNNING → COMPLETED（所有 Skill 检测完成）/ FAILED（不可恢复错误）。

#### 场景: 正常完成

- **当** 所有选中的 Skill 检测完毕（无论每项是 PASS 还是 FAIL）
- **那么** 系统将 Task 状态更新为 COMPLETED，计算整体得分和通过率

#### 场景: SSH 连接失败导致 FAILED

- **当** 在检测过程中 SSH 连接断开且重连失败
- **那么** 系统将 Task 状态更新为 FAILED，记录失败原因和已完成的 Skill 检测结果

### 需求: SSH 连接池管理

系统必须按 `host:port` 维度维护 SSH Session 连接池，同一 Task 内多次命令执行复用同一 Session。Task 完成后归还或关闭连接。

#### 场景: Session 复用

- **当** 同一 Task 需要对同一目标容器执行多条命令
- **那么** 系统从连接池中获取已有 Session，重复使用而不新建连接

#### 场景: Session 超时释放

- **当** SSH Session 空闲时间超过 `idle-timeout` 配置（默认 60 秒）
- **那么** 系统自动关闭该 Session 并从连接池中移除

### 需求: 线下回放模式

DetectionOrchestrator 必须支持「线下回放」模式，对上传的执行记录执行 AI 判定，而不通过 SSH 实时执行命令。

#### 场景: 回放模式读取预录结果

- **当** 系统进入线下回放模式（`executeOfflineReplay` 被调用）
- **那么** 系统从上传的 `execution_results/{skillId}.json` 文件中读取每条命令的 `command`、`stdout`、`stderr`、`exitCode` 字段，构造 `ExecutionResult` 对象，送交 `AiClientService.judgeExecution()` 进行 AI 判定

#### 场景: 回放模式跳过 SSH 执行

- **当** 线下回放模式处理检测命令
- **那么** 系统不调用 `SshExecutionService.execute()` 或 `SshExecutionService.executeRaw()`，所有命令输出来源于上传结果文件

#### 场景: 回放模式使用上传 Skill

- **当** 上传结果包中包含 `evolved_skills/` 目录
- **那么** 回放模式使用合并后的 Skill 定义（上传 Skill + 平台 Skill 合并）进行 Context 匹配和 AI 判定，而非仅使用平台原有 Skill

#### 场景: 回放模式仍执行 AI 判定

- **当** 执行线下回放
- **那么** 每条命令的 AI 判定调用（`AiClientService.judgeExecution`）与在线模式使用完全相同的 Prompt 模板和判定逻辑，AI 日志按 `logs/{taskId}/{skillId}.jsonl` 正常记录

#### 场景: 回放模式不支持命令进化

- **当** AI 在回放模式中返回 `EVOLVE` 判定
- **那么** 系统记录该判定到 `ExecutionRecord`，但不执行 `nextCommand`（因为目标容器不可达），直接跳过继续下一条命令

#### 场景: 回放模式不支持上下文进化

- **当** AI 在回放模式中返回 `ENV_MISMATCH` 判定
- **那么** 系统记录该判定到 `ExecutionRecord`，记录 WARN 级别日志，不触发上下文进化，继续使用当前 Context 处理剩余命令

#### 场景: 回放模式正常生成报告

- **当** 所有 Skill 的回放判定完成
- **那么** 系统调用 `AiClientService.generateReport()` 生成报告，与在线模式使用相同的 Prompt 模板，调用 `ReportGenerationService.generateAndPersist()` 持久化

### 需求: 回放入口端点

系统必须在结果上传校验通过后自动触发回放，不需要用户手动触发。

#### 场景: 上传成功后自动触发

- **当** `POST /api/tasks/{taskId}/results/upload` 的 ZIP 校验通过
- **那么** 系统通过 `CompletableFuture.runAsync` 自动启动回放，无需额外的 API 调用

#### 场景: 回放异步执行

- **当** 回放流程启动
- **那么** 回放在独立线程池中执行（复用 `DetectionOrchestrator` 的 4 线程池），不阻塞上传 API 的 HTTP 响应
