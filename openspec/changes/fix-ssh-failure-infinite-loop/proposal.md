## 为什么

当 SSH 连接目标容器持续失败时（如地址不可达），`DetectionOrchestrator` 的 `ENV_MISMATCH` 处理器会无限触发 `context-evolution` + `phase1` 循环，AI 不断生成无用的执行上下文而任务永远无法停止，浪费计算资源并且阻塞线程池。

## 变更内容

- 在 `SshExecutionService` 中增加 SSH 连接健康检查方法，区分"连接失败"和"命令执行失败"
- 在 `DetectionOrchestrator` 的 `ENV_MISMATCH` 和 `EVOLVE` 分支中：若 SSH 根本不可达，立即终止当前 Skill 检测并返回失败报告，不再触发上下文进化
- 为上下文进化循环增加硬上限防止无限循环

## 功能 (Capabilities)

### 新增功能

- `ssh-failure-graceful-degradation`: SSH 连接失败时 Skill 检测优雅降级并终止，不再无限循环

### 修改功能

- `ai-interaction-logging`: 任务失败日志中必须区分 SSH 连接错误和检测命令错误

## 影响

| 范围 | 详情 |
|---|---|
| 代码 | `DetectionOrchestrator.java`（核心修改）、`SshExecutionService.java`（新增健康检查方法）、`ExecutionResult.java`（新增连接失败标记） |
| 配置 | 可选新增 `ssh.connection-healthcheck-timeout-seconds` |
| 规范 | 修改 `ai-interaction-logging`，新增 `ssh-failure-graceful-degradation` |
| API | 无变更 |
| 破坏性 | 无 |
