## 新增需求

### 需求: Skills 磁盘热加载

系统必须在每次检测任务执行前重新扫描 `skills/` 目录下的所有 `.json` 文件，实现热加载，使大模型更新 Skill 配置后下一轮审计立即生效，无需重启服务。

Skill 文件命名规范为 `{skillId}-{timestamp}.json`，其中 timestamp 为 Unix 毫秒时间戳。

#### 场景: 启动时加载 Skills

- **当** 微服务启动
- **那么** 系统扫描 `skills/` 目录，按 skillId 分组，每组取 versionTimestamp 最大的文件，反序列化为 SkillDefinition 对象并缓存至内存 ConcurrentHashMap

#### 场景: 任务执行前重新加载

- **当** 收到创建检测任务请求
- **那么** 系统在执行任何检测逻辑之前，重新执行一次 `skills/` 目录扫描，确保获取最新版本的 Skill 定义

#### 场景: Skills 目录为空

- **当** `skills/` 目录中没有任何 `.json` 文件
- **那么** 系统记录 WARN 日志并返回空列表，不抛出异常

#### 场景: Skill 文件格式非法

- **当** 某个 `.json` 文件无法被 Jackson 反序列化为合法的 SkillDefinition（缺少必填字段或 JSON 格式错误）
- **那么** 系统记录 ERROR 日志（包含文件名和错误原因），跳过该文件继续加载其余合法文件

### 需求: 版本选择

同一个 skillId 可能存在多个时间戳版本的文件（进化产生的新版本）。系统必须选定 versionTimestamp 最大的作为当前生效版本。

#### 场景: 同一 skillId 有多个文件

- **当** `skills/` 目录中存在 `SEC-K8S-CTX-001-1700000000000.json` 和 `SEC-K8S-CTX-001-1700100000000.json`
- **那么** 系统选取 `SEC-K8S-CTX-001-1700100000000.json` 作为该 Skill 的生效版本

#### 场景: skillId 唯一

- **当** 某个 skillId 仅有一个对应文件
- **那么** 系统直接使用该文件，不进行时间戳比较

### 需求: 环境指纹匹配

系统必须根据目标容器的实际环境信息，从 Skill 的 executionContexts 数组中按优先级选择最佳匹配的上下文。

匹配优先级：
1. 精确匹配：osFlavors 包含目标系统 flavor 且 requiredTools 全部可用
2. 模糊匹配：osType 相同但 flavor 不匹配，requiredTools 全部可用
3. 无匹配：触发上下文进化流程

多匹配时优先选择 evolvedAt 最新的 context。

#### 场景: 精确匹配成功

- **当** 目标容器为 Alpine Linux 且已安装 cat、grep、mount，Skill 中存在 contextId 为 `linux-alpine-busybox` 的 executionContext
- **那么** 系统直接选用该 context，不触发进化

#### 场景: 模糊匹配

- **当** 目标容器为 CentOS（osType=linux, flavor=centos），已安装 capsh/cat/mount/grep，但 Skill 中仅有 Debian 和 Alpine 的 context
- **那么** 系统选用 osType 匹配且 requiredTools 全满足的 context，并标记为"非精确匹配"

#### 场景: 无匹配触发进化

- **当** 目标容器的 osType 与所有现有 context 的 osType 均不同（如 Windows Container vs 所有 Linux context）
- **那么** 系统返回 Optional.empty()，由 DetectionOrchestrator 触发上下文进化

### 需求: Skill 进化持久化

系统必须在 Skill 发生进化（命令级或上下文级）后，将更新后的 SkillDefinition 持久化为新的 `.json` 文件，文件名格式为 `{skillId}-{newTimestamp}.json`，并自增 evolutionCount。

#### 场景: 进化后生成新文件

- **当** Skill SEC-K8S-CTX-001 发生了命令级进化，当前 versionTimestamp 为 1700000000000
- **那么** 系统生成新文件 `SEC-K8S-CTX-001-{currentTimeMillis}.json`，versionTimestamp 更新为当前时间戳，evolutionCount 自增 1，旧文件保留

#### 场景: 进化后刷新缓存

- **当** 进化后的 Skill 文件写入磁盘成功
- **那么** 系统更新内存缓存中对应 skillId 的 SkillDefinition

### 需求: 进化历史查询

系统必须支持查询某个 Skill 的全部进化历史版本，按 versionTimestamp 降序排列。

#### 场景: 查询进化历史

- **当** 请求 `GET /api/skills/{skillId}/history`
- **那么** 系统返回该 skillId 下所有版本文件的完整内容列表，按时间戳从新到旧排列
