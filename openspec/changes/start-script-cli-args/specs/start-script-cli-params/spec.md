## 新增需求

### 需求:start.sh 必须支持 --jdk 参数

start.sh 必须接受 `--jdk <path>` 命令行参数，指定 Java 可执行文件的完整路径。该路径的优先级高于 `JAVA_HOME` 环境变量和 `.java_home` 文件。

#### 场景:用户指定 --jdk 参数
- **当** 用户执行 `./start.sh --jdk /opt/jdk-21/bin/java`
- **那么** 脚本必须使用 `/opt/jdk-21/bin/java` 启动 JAR，并输出 `[INFO] 使用 --jdk 参数: /opt/jdk-21/bin/java`

#### 场景:--jdk 指向的路径不存在
- **当** 用户执行 `./start.sh --jdk /nonexistent/bin/java`
- **那么** 脚本必须输出 `[ERROR] --jdk 指定的路径不存在: /nonexistent/bin/java` 并以退出码 1 退出

#### 场景:--jdk 指向的路径不可执行
- **当** 用户执行 `./start.sh --jdk /etc/passwd`
- **那么** 脚本必须输出 `[ERROR] --jdk 指定的路径不可执行: /etc/passwd` 并以退出码 1 退出

### 需求:start.sh 必须支持 --api-key 参数

start.sh 必须接受 `--api-key <key>` 命令行参数，将 API Key 通过 JVM 系统属性 `-Dspring.ai.openai.api-key` 传递给 Java 进程。

#### 场景:用户指定 --api-key 参数
- **当** 用户执行 `./start.sh --api-key sk-abc123`
- **那么** 脚本必须以 `exec java -Dspring.ai.openai.api-key=sk-abc123 -jar ...` 启动 JAR

#### 场景:同时使用 --jdk 和 --api-key
- **当** 用户执行 `./start.sh --jdk /opt/jdk-21/bin/java --api-key sk-abc123`
- **那么** 脚本必须同时使用指定的 JDK 和 API Key

### 需求:start.sh 必须支持 --port 参数

start.sh 必须接受 `--port <port>` 命令行参数，通过 JVM 系统属性 `-Dserver.port` 覆盖服务端口。

#### 场景:用户指定 --port 参数
- **当** 用户执行 `./start.sh --port 9090`
- **那么** 脚本必须以 `exec java -Dserver.port=9090 -jar ...` 启动 JAR

### 需求:start.sh 必须支持 --help 参数

start.sh 必须接受 `--help` 或 `-h` 参数，输出使用说明并退出。

#### 场景:用户请求帮助
- **当** 用户执行 `./start.sh --help`
- **那么** 脚本必须输出所有支持参数的说明文本并以退出码 0 退出

#### 场景:未知参数
- **当** 用户执行 `./start.sh --unknown-flag`
- **那么** 脚本必须输出错误提示、显示帮助信息，并以退出码 1 退出

### 需求:不带参数时保持向后兼容

当 start.sh 没有任何参数时，行为必须与当前版本完全一致。

#### 场景:无参数运行
- **当** 用户执行 `./start.sh` 不传任何参数
- **那么** 脚本必须走原有的 JDK 查找逻辑（JAVA_HOME → .java_home → PATH），API Key 从 application.yml 或环境变量读取
