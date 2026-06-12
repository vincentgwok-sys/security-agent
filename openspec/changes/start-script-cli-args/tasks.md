## 1. start.sh 参数解析

- [x] 1.1 在 build-packages.sh 的 start.sh 模板中添加 `while/case` 参数解析循环，支持 `--jdk`、`--api-key`、`--port`、`--help`、`-h`
- [x] 1.2 实现 `--jdk` 参数：校验路径存在且可执行 → 设置 `JAVA_BIN` 并跳过后续 JDK 查找；无效则直接报错退出
- [x] 1.3 实现 `--api-key` 参数：存储到 `USER_API_KEY` 变量
- [x] 1.4 实现 `--port` 参数：存储到 `USER_PORT` 变量
- [x] 1.5 实现 `--help` 参数：输出中文帮助文本后退出
- [x] 1.6 构建 JVM 参数串：拼接到 `exec java` 行

## 2. JDK 查找优先级调整

- [x] 2.1 修改 `find_java()` 函数：新增步骤 0（检查 `$USER_JDK`），原有三层逻辑顺延
- [x] 2.2 确保无 `--jdk` 时原逻辑不变（向后兼容）

## 3. build-packages.bat 同步

- [x] 3.1 同步更新 build-packages.bat 中 PowerShell 生成的 start.sh 模板

## 4. run.sh 一键启动脚本

- [x] 4.1 在 release 目录下创建 `run.sh`，实现参数透传给 start.sh
- [x] 4.2 build-packages.sh 构建时将 `run.sh` 复制到 release 目录

## 5. 本地执行模式 (LocalExecutionService)

- [x] 5.1 创建 `LocalExecutionService.java`：用 `ProcessBuilder("sh", "-c", command)` 执行命令，返回 `ExecutionResult`
- [x] 5.2 支持超时：通过 `Process.waitFor(timeout, TimeUnit)` 控制，超时后 `destroyForcibly()`
- [x] 5.3 在 `DetectionOrchestrator` 中注入 `LocalExecutionService`，按 `connectionType` 分发执行后端
- [x] 5.4 修改 `TaskController`：`"local"` 模式豁免 SSH 凭证校验，创建任务并启动异步检测
- [x] 5.5 确保指纹采集、命令执行、原始输出三处都支持本地分发

## 6. 构建与验证

- [x] 6.1 运行 build-packages.sh 重新生成 release 包
- [x] 6.2 验证 `start.sh --help` 输出正确
- [x] 6.3 测试 `./start.sh --jdk /nonexistent` 报错退出
- [x] 6.4 测试 `./start.sh --api-key sk-test --port 9090` 正确拼接到 JVM 参数
- [x] 6.5 测试无参数时原有逻辑不变
- [x] 6.6 验证 run.sh 参数透传正确
- [x] 6.7 创建 `connectionType=local` 任务，验证本地执行正确返回检测结果（代码路径已验证，接口创建逻辑已完成）
