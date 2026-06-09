## 修改需求

### 需求: Skills 磁盘热加载

系统必须在每次检测任务执行前重新扫描 `skills/` 目录下的所有 `.json` 文件，实现热加载，使大模型更新 Skill 配置后下一轮审计立即生效，无需重启服务。

`skills/` 目录路径必须基于 `user.dir` 系统属性解析为绝对路径，确保无论 JVM 从哪个工作目录启动，均能正确定位。若 `application.yml` 中已配置为绝对路径（如 `/data/agent/skills`），则保持不变。

#### 场景: 启动时加载 Skills

- **当** 微服务启动
- **那么** 系统将 `application.yml` 中配置的 `security-agent.skills.directory` 解析为绝对路径后，扫描该目录，按 skillId 分组，每组取 versionTimestamp 最大的文件，反序列化为 SkillDefinition 对象并缓存至内存 ConcurrentHashMap

#### 场景: 相对路径解析为绝对路径

- **当** 配置为 `./skills`（相对路径）
- **那么** 系统运行时解析为 `{user.dir}/skills`，无论 JVM 从哪个目录启动

#### 场景: 绝对路径配置保持不变

- **当** 配置为 `/data/agent/skills`
- **那么** 系统直接使用该路径，不拼接 `user.dir`

#### 场景: 任务执行前重新加载

- **当** 收到创建检测任务请求
- **那么** 系统在执行任何检测逻辑之前，重新执行一次目录扫描，确保获取最新版本的 Skill 定义

#### 场景: Skills 目录为空

- **当** Skills 目录中没有任何 `.json` 文件
- **那么** 系统记录 WARN 日志并返回空列表，不抛出异常

#### 场景: Skill 文件格式非法

- **当** 某个 `.json` 文件无法被 Jackson 反序列化为合法的 SkillDefinition（缺少必填字段或 JSON 格式错误）
- **那么** 系统记录 ERROR 日志（包含文件名和错误原因），跳过该文件继续加载其余合法文件
