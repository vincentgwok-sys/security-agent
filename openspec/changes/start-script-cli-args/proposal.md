## 为什么

Release 包的目标用户场景是 Linux 容器（Docker/containerd），但在容器中无法修改环境变量，且不支持交互式文件编辑。当前 start.sh 的 JDK 查找依赖 `JAVA_HOME` 环境变量和 `.java_home` 文件，API Key 依赖 `application.yml`。同时，用户可能需要将 Agent 直接部署到目标容器上执行本地检测（无需 SSH），但当前检测流程强制要求 SSH 连接。需要通过 CLI 参数、统一入口脚本和本地执行模式来适配容器场景。

## 变更内容

1. **start.sh 支持 CLI 参数传入 JDK 路径**：`--jdk /path/to/jdk-21`，优先级最高
2. **start.sh 支持 CLI 参数传入 API Key**：`--api-key sk-xxx`，覆盖环境变量和 application.yml
3. **release 目录新增一键运行脚本**：`run.sh`，适配容器单进程场景
4. **新增本地执行模式**：检测任务支持 `connectionType=local`，直接在本机执行命令，无需 SSH

## 功能 (Capabilities)

### 新增功能
- `start-script-cli-params`: start.sh 支持 `--jdk`、`--api-key`、`--port`、`--help` 命令行参数
- `one-click-run-script`: release 目录下的 `run.sh`，一键启动后端服务
- `local-execution`: 检测任务支持本地执行模式，通过 ProcessBuilder 直接在本机运行命令，无需 SSH 连接

### 修改功能
- `jdk-resolution-logic`: JDK 查找优先级变更为：CLI `--jdk` > `JAVA_HOME` > `.java_home` > PATH

## 影响

- `container-security-agent/build-packages.sh`：start.sh 模板需重写参数解析逻辑
- `container-security-agent/build-packages.bat`：同步更新 start.sh 模板
- `container-security-agent/release/run.sh`：新增一站式启动脚本
- `container-security-agent/src/main/java/com/security/agent/service/LocalExecutionService.java`：**新增**本地命令执行服务
- `container-security-agent/src/main/java/com/security/agent/service/DetectionOrchestrator.java`：根据 `connectionType` 分发到 SSH 或本地执行
- `container-security-agent/src/main/java/com/security/agent/controller/TaskController.java`：支持 `"local"` 连接类型，豁免 SSH 凭证校验
- `container-security-agent/release/README.txt`：更新使用说明
- `README.md`：更新 Release 包运行指导，添加容器部署和本地执行章节
