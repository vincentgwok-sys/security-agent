## 上下文

`DetectionOrchestrator` 通过 `ENV_MISMATCH` 处理器触发上下文进化，然后重新执行整个命令列表。当 SSH 连接根本不可达时（前文日志中 `127.0.0.444` 不可解析），每次执行都失败，AI 返回 `ENV_MISMATCH`，循环永远继续。`SshExecutionService` 将所有异常统一包装为 `ExecutionResult(exitCode=-1)`，上层无法区分 SSHD 连接异常和命令执行异常。

当前相关类：
- `ExecutionResult`：含 stdout, stderr, exitCode, blocked — 无连接状态区分
- `SshExecutionService.execute()`：第 81-88 行 catch 所有异常统一返回
- `DetectionOrchestrator.executeSkillDetection()`：第 175-187 行 ENV_MISMATCH 无条件触发进化

## 目标 / 非目标

**目标：**
- SSH 连接不可达时立即终止 Skill 检测，返回失败报告
- 上下文进化次数硬上限防止无限循环
- ExecutionResult 能区分连接错误和命令执行错误

**非目标：**
- 不实现重试逻辑或指数退避
- 不改变 SSH 连接池/会话缓存行为
- 不修改 AI prompt 或判定逻辑

## 决策

### 决策 1：通过 `ExecutionResult.connectionError` 标记区分连接失败

在 `ExecutionResult` 新增 `boolean connectionError` 字段（默认 false）。`SshExecutionService` 在 catch 块中将 `getOrCreateSession()` 抛出的连接异常（`ConnectException`, `UnresolvedAddressException` 或其父类 `IOException`）对应的 result 标记为 `connectionError=true`，而命令执行超时等不标记。

**替代方案**：引入 `ExecutionResultType` 枚举 → 引入新类型虽然更语义化，但对此变更是过度设计。一个 boolean 字段足够区分。

### 决策 2：在 `SshExecutionService` 中分离连接异常与执行异常

`execute()` 和 `executeRaw()` 的 catch 块细化：

```java
} catch (Exception e) {
    boolean isConnectionError = isConnectionException(e);
    return ExecutionResult.builder()
            .connectionError(isConnectionError)
            .stderr("SSH 执行异常: " + e.getMessage())
            .exitCode(-1)
            .build();
}
```

`isConnectionException()` 检查异常链中是否包含 SSHD 连接级异常。SSHD 的连接异常（`SshException`, `java.net.ConnectException` 等）是 `IOException` 的子类，区别于命令执行中的 `SSHClientChannelException`。

### 决策 3：ENV_MISMATCH 时优先检查连接状态

在 `DetectionOrchestrator` 的 `ENV_MISMATCH` 分支（第 175 行）中，先检查 `result.isConnectionError()`：若为 true，直接组装失败报告返回，不进入上下文进化。

```java
case "ENV_MISMATCH":
    if (result.isConnectionError()) {
        log.error("[{}] SSH 连接不可达，终止检测", skillId);
        return buildConnectionFailedReport(skill, result);
    }
    // existing context evolution logic...
```

### 决策 4：上下文进化硬上限

在 `executeSkillDetection()` 中新增 `maxContextEvolutions` 计数器（默认 3），ENV_MISMATCH 每次触发上下文进化前检查是否已达上限。

**替代方案**：将上下文进化也纳入 `maxEvolveRounds` → 两个层级含义不同，应分开计数。

## 风险 / 权衡

- **假阳性连接错误**：如果某些中间件（如 HTTP 代理）抛出的异常被误判为连接失败 → 通过检查异常类型层级（`java.net` 包 + SSHD 核心类）精确匹配来缓解
- **硬上限可能过小**：3 次上下文进化可能不够 → 通过配置项 `security-agent.detection.max-context-evolutions:3` 允许用户调整
- **向后兼容**：`ExecutionResult.connectionError` 默认 false，已有代码无需改动
