## 修改需求

### 需求:JDK 查找优先级

start.sh 查找 Java 的优先级必须为：
1. CLI `--jdk` 参数（新增，最高优先级）
2. `JAVA_HOME` 环境变量
3. `.java_home` 本地配置文件
4. `PATH` 中的 `java` 命令

当高优先级来源可用且有效时，不得检查低优先级来源。

#### 场景:--jdk 存在且有效时，忽略 JAVA_HOME
- **当** 用户设置 `JAVA_HOME=/old/jdk` 并执行 `./start.sh --jdk /new/jdk-21/bin/java`
- **而且** `/new/jdk-21/bin/java` 存在且可执行
- **那么** 脚本必须使用 `/new/jdk-21/bin/java`，不检查 `JAVA_HOME`

#### 场景:无 --jdk 时回退到 JAVA_HOME
- **当** 用户未指定 `--jdk`，但设置了 `JAVA_HOME=/usr/lib/jvm/jdk-21`
- **而且** `$JAVA_HOME/bin/java` 存在且可执行
- **那么** 脚本必须使用 `$JAVA_HOME/bin/java`

#### 场景:无 --jdk 和 JAVA_HOME 时回退到 .java_home
- **当** 用户未指定 `--jdk`，未设置 `JAVA_HOME`
- **而且** `.java_home` 文件存在且指向有效 JDK
- **那么** 脚本必须使用 `.java_home` 中指定的 Java

#### 场景:所有来源均无效时报错
- **当** 用户未指定 `--jdk`，未设置 `JAVA_HOME`，`.java_home` 不存在
- **而且** PATH 中也没有 `java`
- **那么** 脚本必须输出错误信息并以退出码 1 退出
