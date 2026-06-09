## 新增需求

### 需求: 命令安全规则热加载

系统必须在启动时和每次检测任务执行前重新扫描 `rules/` 目录下的所有 `.json` 规则文件，同时提供手动触发重载的 API 端点。

#### 场景: 启动时加载规则

- **当** 微服务启动
- **那么** 系统扫描 `rules/` 目录，反序列化所有规则文件，合并规则集（相同 ruleId 取最新版本），按 action 优先级排序（BLOCK > WARN > ALLOW），预编译所有 REGEX 类型的 Pattern 对象

#### 场景: API 手动触发重载

- **当** 收到 `POST /api/rules/reload` 请求
- **那么** 系统重新扫描 `rules/` 目录，替换当前生效的规则集，返回重载后的规则数量和分类统计

#### 场景: 规则文件格式非法

- **当** 某个规则文件 JSON 格式错误或缺少必填字段
- **那么** 系统记录 ERROR 日志（含文件名和错误原因），跳过该文件继续加载其余合法规则文件

### 需求: 命令过滤

所有经由 SSH 发送至目标容器执行的命令，必须先经规则引擎校验。校验结果分为三级：

- **BLOCK**：拒绝执行，命令不发送到目标容器
- **WARN**：记录警告日志后放行，命令继续执行
- **ALLOW**：直接放行

规则优先级：BLOCK > WARN > ALLOW。一条命令同时命中多条规则时取最严格判定。

#### 场景: 危险命令被拦截

- **当** AI 生成的命令为 `rm -rf /etc/config`
- **那么** 规则引擎匹配到 R-001（DESTRUCTIVE_DELETE）规则，返回 BLOCK 判定，命令不被发送到目标容器，系统记录 ERROR 级别日志

#### 场景: 安全命令被放行

- **当** AI 生成的命令为 `cat /proc/1/status`
- **那么** 规则引擎匹配到白名单规则 R-007（ALLOWLIST_SAFE_READ），返回 ALLOW 判定，命令正常发送执行

#### 场景: 敏感操作被警告

- **当** AI 生成的命令为 `systemctl stop docker`
- **那么** 规则引擎匹配到 R-004（SERVICE_CONTROL）规则，返回 WARN 判定，记录 WARN 级别日志，命令仍发送执行

### 需求: 默认策略

当命令不匹配任何已有规则时，系统必须使用配置中的 `defaultAction` 决定处置方式。默认值为 `BLOCK`。

#### 场景: 未知命令被默认拦截

- **当** 待执行命令为某条未在任何规则中定义的命令，且 `defaultAction=BLOCK`
- **那么** 系统返回 BLOCK 判定，reason 说明"命令未命中任何放行规则，被默认策略拦截"

#### 场景: 默认策略可配置

- **当** 配置项 `security-agent.rules.default-action` 设置为 `ALLOW`
- **那么** 未命中规则的命令被放行执行

### 需求: 三种匹配类型

规则必须支持三种匹配模式：

- **REGEX**：Java 正则表达式匹配
- **EXACT**：精确字符串匹配（trim 后比较）
- **PREFIX**：前缀匹配

#### 场景: REGEX 匹配

- **当** 规则 matchType 为 REGEX，pattern 为 `rm\s+-[a-zA-Z]*[rf]`，命令为 `rm -rf /tmp`
- **那么** 正则编译后执行 find() 匹配成功

#### 场景: EXACT 匹配

- **当** 规则 matchType 为 EXACT，pattern 为 `whoami`，命令为 `whoami`
- **那么** trim 后精确比较成功

#### 场景: PREFIX 匹配

- **当** 规则 matchType 为 PREFIX，pattern 为 `kubectl delete`，命令为 `kubectl delete pod my-pod`
- **那么** 命令以 pattern 开头，匹配成功

### 需求: 命中日志记录

规则命中时必须记录结构化日志，包含 taskId、skillId、规则 ID、判定结果和原始命令。

#### 场景: BLOCK 命中日志

- **当** 命令被 BLOCK 规则命中
- **那么** 系统使用 ERROR 级别记录：`[taskId][skillId] 规则命中 R-xxx (BLOCK): <command> — <message>`

#### 场景: WARN 命中日志

- **当** 命令被 WARN 规则命中
- **那么** 系统使用 WARN 级别记录：`[taskId][skillId] 规则命中 R-xxx (WARN): <command> — <message>`

#### 场景: ALLOW 命中日志

- **当** 命令被 ALLOW 规则命中
- **那么** 系统使用 DEBUG 级别记录：`[taskId][skillId] 规则命中 R-xxx (ALLOW): <command>`

### 需求: 被拦截命令触发进化

当命令被规则引擎 BLOCK 时，系统必须将此结果反馈给 AI，触发 EVOLVE 流程，由 AI 生成一条等效但安全的替代命令。

#### 场景: BLOCK 后 AI 生成替代命令

- **当** 命令 `rm -rf /etc/config` 被规则引擎拦截
- **那么** 系统构造一条 EVOLVE 判定（含拦截原因），调用 AI 让大模型生成等效安全的替代命令（如 `cat /etc/config` 或 `ls -la /etc/config`），使用替代命令重新执行检测
