## 上下文

当前 start.sh 通过以下方式获取配置：

- **JDK**：三层查找（`JAVA_HOME` → `.java_home` → `PATH`），全部依赖文件系统或预置环境变量
- **API Key**：从 `application.yml` 读取（通过 Spring Boot 的 `${SECURITY_AGENT_AI_API_KEY:default}`）
- **端口**：从 `application.yml` 读取

容器场景约束：
- 无法在 Dockerfile 外修改环境变量
- 不支持交互式文件编辑
- 需要使用 `exec` 确保信号转发（已修复）
- 单容器通常只运行一个进程（后端 JAR 自带前端即可）
- 最小镜像（如 `eclipse-temurin:21-jre`）可能没有 Python
- 用户可能将 Agent 直接部署到目标容器上，需要在本机执行检测命令而非通过 SSH

当前检测执行的局限：
- `DetectionOrchestrator` 所有命令执行都硬编码走 `SshExecutionService`
- 没有本地命令执行的后端
- `connectionType` 支持 `"ssh"`、`"kubectl"`、`"offline"`，但没有 `"local"`

## 目标 / 非目标

**目标：**
1. start.sh 接收 `--jdk` 参数作为最优先 JDK 来源
2. start.sh 接收 `--api-key` 参数作为最优先 API Key 来源
3. start.sh 接收 `--port` 参数覆盖服务端口
4. release 目录下新增 `run.sh`，在容器中一键启动后端（内嵌前端）
5. 新增本地执行模式（`connectionType=local`），Agent 直接在本机运行检测命令
6. 保持向后兼容——不带参数时行为不变

**非目标：**
- 不修改 start.bat（Windows 本身支持环境变量，CLI 参数非必需）
- 不在 run.sh 中实现进程管理（容器只跑单进程）
- 不引入 getopt/getopts 以外的依赖
- 不修改 SSH 执行路径（本地模式与 SSH 互斥，不同时使用）

## 决策

### 决策 1：参数解析用 `while + case`，不依赖 getopt

**理由**：`getopt` 在 BusyBox（Alpine）和 macOS 上行为不一致。`while + case` 是纯 bash 语法，兼容性最好。

```bash
while [[ $# -gt 0 ]]; do
    case "$1" in
        --jdk)      USER_JDK="$2"; shift 2 ;;
        --api-key)  USER_API_KEY="$2"; shift 2 ;;
        --port)     USER_PORT="$2"; shift 2 ;;
        --help)     show_help; exit 0 ;;
        *)          echo "未知参数: $1"; show_help; exit 1 ;;
    esac
done
```

### 决策 2：API Key 通过 JVM 系统属性传入，不在磁盘落文件

**理由**：避免在容器文件系统留下明文凭据。使用 `-Dspring.ai.openai.api-key=$API_KEY` 传给 Java。

优先级：`--api-key` > `SECURITY_AGENT_AI_API_KEY` 环境变量 > `application.yml` 中的值

```bash
JAVA_OPTS=""
if [ -n "$USER_API_KEY" ]; then
    JAVA_OPTS="$JAVA_OPTS -Dspring.ai.openai.api-key=$USER_API_KEY"
fi
exec "$JAVA_BIN" $JAVA_OPTS -jar "$SCRIPT_DIR/container-security-agent-1.0.0.jar"
```

### 决策 3：端口覆盖同样用 JVM 系统属性

```bash
if [ -n "$USER_PORT" ]; then
    JAVA_OPTS="$JAVA_OPTS -Dserver.port=$USER_PORT"
fi
```

### 决策 4：run.sh 是后端的一键启动包装，不启动前端（后端 JAR 已内嵌）

**理由**：容器场景单进程最佳实践。后端 JAR 已内嵌前端，访问 8080 端口即可。

```bash
#!/usr/bin/env bash
# run.sh — 容器一键启动脚本
# 用法: ./run.sh --api-key sk-xxx [--jdk /path/to/jdk]
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$SCRIPT_DIR/container-security-agent-backend-1.0.0"
cd "$BACKEND_DIR"
exec ./start.sh "$@"
```

### 决策 5：参数逐级透传

run.sh 接收所有参数并透传给 start.sh。这样用户只关心一个入口点：

```bash
docker run ... ghcr.io/xxx/security-agent:1.0.0 ./run.sh --api-key sk-xxx --jdk /opt/jdk-21/bin/java
```

### 决策 6：新增 `LocalExecutionService` 而非改造 SshExecutionService

**理由**：`SshExecutionService` 的方法签名包含 `host`、`port`、`user`、`password` 参数，与本地执行语义不兼容。与其抽取抽象层增加复杂度，不如新增独立类，在 `DetectionOrchestrator` 中按 `connectionType` 做简单分发。

```java
@Service
public class LocalExecutionService {
    public ExecutionResult execute(String command, int timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        // ... start process, wait with timeout, capture stdout/stderr
        return new ExecutionResult(stdout, stderr, exitCode, false, false);
    }
}
```

### 决策 7：DetectionOrchestrator 通过 `connectionType` 分发

在 `executeSkillDetection()` 中，命令执行点（目前 3 处：环境指纹采集、环境原始输出、检测命令执行）按 `task.getConnectionType()` 选择执行后端：

```java
if ("local".equals(task.getConnectionType())) {
    result = localExecutionService.execute(command, timeout);
} else {
    result = sshService.execute(ip, port, user, pwd, command, timeout);
}
```

不对现有 `SshExecutionService` 做结构性改动，避免影响已有 SSH 功能。

### 决策 8：TaskController 本地模式豁免 SSH 凭证校验

`connectionType="local"` 时，`targetIp`、`sshUser`、`sshPassword` 均非必填。校验逻辑：

```java
if (!"offline".equals(connType) && !"local".equals(connType)) {
    // 要求 targetIp, sshUser, sshPassword 非空
}
```

本地模式的创建流程与其他非 offline 模式一致——异步调用 `orchestrator.executeDetection()`，只是执行后端不同。

## 风险 / 权衡

- **[风险]** API Key 作为命令行参数在 `ps aux` 中可见 → 在 run.sh 中提示用户优先使用环境变量
- **[风险]** 参数透传链（run.sh → start.sh）增加调试复杂度 → `--help` 在两级都可用
