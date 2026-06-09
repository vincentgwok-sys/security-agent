## 1. ExecutionResult 模型增强

- [x] 1.1 在 `ExecutionResult.java` 中新增 `boolean connectionError` 字段，默认 `false`

## 2. SSH 连接失败检测

- [x] 2.1 在 `SshExecutionService.java` 中新增 `isConnectionException(Exception)` 私有方法，检查异常链是否包含 SSHD 连接级异常（`IOException` 子类：`ConnectException`、`UnresolvedAddressException`、`SshException`、`SocketException`）
- [x] 2.2 修改 `execute()` 的 catch 块，调用 `isConnectionException()` 并将结果写入 `ExecutionResult.connectionError`
- [x] 2.3 修改 `executeRaw()` 的 catch 块，同样设置 `connectionError`

## 3. 检测流程终止机制

- [x] 3.1 在 `DetectionOrchestrator.java` 中新增 `maxContextEvolutions` 配置字段和 `contextEvolutionCount` 本地变量
- [x] 3.2 在 `ENV_MISMATCH` 分支最前面检查 `result.isConnectionError()`，若为 true 返回 `buildConnectionFailedReport()`
- [x] 3.3 在 `ENV_MISMATCH` 分支触发 `evolveNewContext()` 前检查 `contextEvolutionCount >= maxContextEvolutions`，达上限则终止
- [x] 3.4 新增 `buildConnectionFailedReport()` 方法，组装 SSH 连接失败的 SkillReport
- [x] 3.5 在 `application.yml` 中添加 `security-agent.detection.max-context-evolutions: 3` 配置

## 4. AI 日志记录

- [x] 4.1 在 `DetectionOrchestrator.buildConnectionFailedReport()` 中调用 `AiLogService.log()` 记录一条 `phase="ssh-connection-error"` 的日志条目

## 5. 前端日志查看器适配

- [x] 5.1 在 `AiLogViewer.vue` 的 `phaseColor()` 和 `phaseLabel()` 中添加 `"ssh-connection-error"` 映射（红色标签 + "SSH 连接失败"标签）

## 6. 验证

- [ ] 6.1 使用不可达 IP 创建任务，确认任务能快速终止返回 FAIL 状态而非卡住
- [ ] 6.2 使用可达 IP 创建任务，确认正常检测不受影响
