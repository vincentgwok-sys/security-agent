## 新增需求

### 需求:release 目录必须包含 run.sh 一键启动脚本

release 目录下必须存在 `run.sh`，提供容器场景的一键启动入口。脚本接收与 start.sh 相同的参数并透传。

#### 场景:run.sh 透传参数
- **当** 用户执行 `release/run.sh --api-key sk-abc --jdk /opt/jdk-21/bin/java`
- **那么** run.sh 必须将全部参数原样传递给 `container-security-agent-backend-1.0.0/start.sh`

#### 场景:run.sh 无参数运行
- **当** 用户执行 `release/run.sh` 不传参数
- **那么** run.sh 必须调用 start.sh 不带参数（走默认 JDK 查找逻辑）

#### 场景:run.sh 在容器中运行
- **当** Dockerfile 设置 `CMD ["./run.sh", "--api-key", "sk-xxx"]`
- **那么** 容器启动后必须直接启动 Java 进程（通过 exec 链确保信号转发）

### 需求:run.sh 必须支持 --help

run.sh 必须支持 `--help`，输出自身说明和 start.sh 的完整参数列表。

#### 场景:查看帮助
- **当** 用户执行 `release/run.sh --help`
- **那么** 脚本必须输出使用说明和所有可用参数
