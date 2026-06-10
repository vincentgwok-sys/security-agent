## 新增需求

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
