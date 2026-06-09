# 容器运行时安全审计 Agent — Claude Code 构建提示词

> 将以下提示词直接输入 Claude Code，即可开始构建项目。

---

## 项目概览

请帮我构建一个**容器运行时安全审计 Agent**，技术栈如下：

| 层级 | 技术选型 |
|------|----------|
| 语言 / 运行时 | Java 21 |
| 框架 | Spring Boot 3.x |
| AI 调用 | Spring AI（OpenAI 兼容协议） |
| JSON 序列化 | Jackson |
| 前端 | Vue 3（Vite 构建） |
| 日志 | SLF4J + Logback |
| SSH | Apache SSHD（连接池复用） |
| 构建工具 | Maven |

---

## 一、项目结构

请按以下结构组织代码：

```
container-security-agent/
├── pom.xml
├── src/main/java/com/security/agent/
│   ├── AgentApplication.java                 # Spring Boot 主入口
│   ├── config/
│   │   ├── AiConfig.java                     # Spring AI / OpenAI 客户端配置
│   │   ├── SshPoolConfig.java                # SSH 连接池配置
│   │   ├── JacksonConfig.java                # Jackson ObjectMapper 配置
│   │   └── WebMvcConfig.java                 # CORS / 静态资源配置
│   ├── model/
│   │   ├── SkillDefinition.java              # Skill 核心数据（含 executionContexts 数组）
│   │   ├── ExecutionContext.java             # 单个执行上下文（环境指纹 + 检测命令）
│   │   ├── EnvironmentFingerprint.java       # 环境指纹（OS/flavor/tools/kernel/shell/arch）
│   │   ├── ExecutionResult.java              # SSH 命令执行结果（stdout/stderr/exitCode）
│   │   ├── ExecutionRecord.java              # 单次命令执行的完整记录
│   │   ├── DetectionTask.java                # 检测任务实体
│   │   ├── TaskStatus.java                   # 任务状态枚举
│   │   ├── ReportData.java                   # 最终报告数据结构
│   │   ├── SkillReport.java                  # 单个 Skill 的检测报告
│   │   ├── AiVerdict.java                    # 阶段一 AI 判定（PASS/FAIL/EVOLVE/ENV_MISMATCH）
│   │   ├── ContextMatchResult.java           # 阶段零 AI 上下文匹配结果
│   │   ├── AiReportResponse.java             # 阶段二 AI 报告 JSON
│   │   ├── CommandRule.java                  # 命令执行规则（黑白名单 + 匹配模式）
│   │   └── RuleVerdict.java                  # 规则判定结果（ALLOW/BLOCK/WARN）
│   ├── service/
│   │   ├── SkillLoaderService.java           # Skills 磁盘扫描 + 热加载 + 环境匹配
│   │   ├── SshExecutionService.java          # SSH 连接池 + 命令执行（内嵌规则过滤）
│   │   ├── CommandRuleService.java           # 命令安全规则引擎（黑白名单 + 热加载）
│   │   ├── DetectionOrchestrator.java        # 核心检测编排（两级进化）
│   │   ├── ReportGenerationService.java      # 报告生成（阶段二 AI + HTML）
│   │   ├── TaskManagementService.java        # 任务 CRUD
│   │   ├── AiClientService.java              # Spring AI 调用封装（含 5 种 Prompt）
│   │   └── PromptTemplateEngine.java         # Prompt 模板引擎（Mustache 渲染）
│   ├── controller/
│   │   ├── TaskController.java               # 创建任务 / 任务列表 API
│   │   ├── ReportController.java             # 查看报告 API + HTML 渲染
│   │   ├── SkillController.java              # 查看 Skill 列表 / 内容 / 进化历史 API
│   │   └── RuleController.java               # 查看规则列表 / 内容 / 热加载重载 API
│   └── util/
│       ├── JsonCleaner.java                  # 大模型 JSON 清洗 + 自我修复
│       └── DateTimeUtil.java                 # 时间戳 / 日期格式化工具
├── src/main/resources/
│   ├── application.yml
│   ├── prompts/                              # Prompt 模板文件（.mustache）
│   │   ├── phase0-system.mustache
│   │   ├── phase0-user.mustache
│   │   ├── phase1-system.mustache
│   │   ├── phase1-user.mustache
│   │   ├── context-evolution-system.mustache
│   │   ├── context-evolution-user.mustache
│   │   ├── phase2-system.mustache
│   │   └── phase2-user.mustache
│   ├── static/                               # Vue 前端构建产物
│   └── templates/
│       └── report.html                       # Thymeleaf 报告模板（可选）
├── skills/                                   # 运行时 Skill 文件目录
│   ├── SEC-K8S-CTX-001-1700000000000.json
│   ├── SEC-K8S-CTX-002-1700000000000.json
│   ├── SEC-K8S-CTX-003-1700000000000.json
│   └── SEC-K8S-CTX-004-1700000000000.json
├── rules/                                    # 命令安全规则目录
│   └── command-rules.json                    # 默认黑白名单规则文件
├── reports/                                  # 生成的报告持久化目录
├── frontend/                                 # Vue 3 前端源码
│   ├── package.json
│   ├── vite.config.js
│   ├── index.html
│   └── src/
│       ├── App.vue
│       ├── main.js
│       ├── router/
│       │   └── index.js
│       ├── views/
│       │   ├── CreateTask.vue
│       │   ├── TaskList.vue
│       │   ├── ReportView.vue
│       │   └── SkillView.vue
│       ├── components/
│       │   ├── StatusBadge.vue
│       │   ├── RiskLevelTag.vue
│       │   └── CommandOutput.vue
│       └── api/
│           └── index.js
└── README.md
```

---

## 二、核心数据结构

### 2.1 Skill 定义（`skills/*.json`）—— 多环境执行上下文

一个 Skill 代表一个**安全检测场景**。同一个检测场景在不同系统环境（Debian/Ubuntu、Alpine/BusyBox、CentOS/RHEL、Windows Container 等）中，实现检测的**命令和预期行为可能完全不同**。因此 Skill 采用 `executionContexts` 数组来管理多套执行逻辑，每套上下文绑定了自己的前置条件和命令集。

#### 核心数据结构（完整示例）

```json
{
  "skillId": "SEC-K8S-CTX-001",
  "skillName": "特权模式与高危 Capabilities 探测",
  "versionTimestamp": 1773057600000,
  "riskLevel": "CRITICAL",
  "description": "检测容器是否以 --privileged 模式运行，或者在其 securityContext 中被赋予了 CAP_SYS_ADMIN、CAP_SYS_PTRACE 等高危权限，从而评估其逃逸风险。",
  "evolutionCount": 0,
  "reportMetadata": {
    "remediationFocus": "重点针对 Kubernetes Pod/Container 的 securityContext 进行加固，指导开发人员 drop ALL capabilities 并禁用特权模式。"
  },
  "executionContexts": [
    {
      "contextId": "linux-debian-ubuntu-standard",
      "environmentFingerprint": {
        "osType": "linux",
        "osFlavors": ["debian", "ubuntu"],
        "shellType": "bash",
        "requiredTools": ["capsh", "cat", "mount", "grep"],
        "optionalTools": ["getfattr", "attr"],
        "minKernelVersion": "3.10.0",
        "arch": ["amd64", "arm64"]
      },
      "envCheckCommands": [
        "uname -s",
        "cat /etc/os-release | grep ^ID=",
        "which capsh cat mount grep",
        "echo $SHELL",
        "uname -r",
        "uname -m"
      ],
      "executionLogic": {
        "expectedBehavior": "如果容器安全隔离正常，任何特权操作或高危工具调用应被拒绝（如 Operation not permitted），且进程的 CapEff 掩码不应包含特权位。",
        "detectionCommands": [
          "capsh --print",
          "cat /proc/1/status | grep CapEff",
          "mount -t tmpfs none /tmp/test_mount 2>&1",
          "cat /proc/1/status | grep -E '^Seccomp:'"
        ]
      },
      "evolvedFrom": null,
      "evolvedAt": null
    },
    {
      "contextId": "linux-alpine-busybox",
      "environmentFingerprint": {
        "osType": "linux",
        "osFlavors": ["alpine"],
        "shellType": "ash",
        "requiredTools": ["cat", "grep", "mount"],
        "optionalTools": [],
        "minKernelVersion": "3.10.0",
        "arch": ["amd64", "arm64"]
      },
      "envCheckCommands": [
        "uname -s",
        "cat /etc/os-release | grep ^ID=",
        "which cat grep mount",
        "echo $SHELL",
        "uname -r",
        "uname -m"
      ],
      "executionLogic": {
        "expectedBehavior": "与 Debian 环境一致：特权操作应被拒绝，进程 CapEff 掩码不应含特权位。Alpine 中无 capsh，需用其他方式检测。",
        "detectionCommands": [
          "cat /proc/1/status | grep CapEff",
          "cat /proc/1/status | grep -E '^Seccomp:'",
          "mount 2>&1 | grep -E '(proc|sys|tmpfs)'",
          "cat /proc/self/mounts | grep -E '/proc|/sys' | head -5"
        ]
      },
      "evolvedFrom": "linux-debian-ubuntu-standard",
      "evolvedAt": 1773120000000
    }
  ]
}
```

#### 关键设计说明

| 概念 | 说明 |
|------|------|
| **executionContexts** | 数组，每个元素是一套适配特定环境的完整检测方案。同一个 Skill 可以有 N 个 context |
| **contextId** | 唯一标识该上下文，命名规范：`{osType}-{flavor}-{variant}` |
| **environmentFingerprint** | 环境的"指纹"信息，用于匹配目标容器环境 |
| **osFlavors** | 系统发行版列表，一个 context 可覆盖多个相近发行版 |
| **requiredTools** | 执行 detectionCommands 所必需的命令行工具 |
| **optionalTools** | 可选工具，缺失不影响核心检测但可能影响某些命令 |
| **envCheckCommands** | 环境探针命令，先执行这些命令收集环境信息，再匹配最佳 context |
| **evolvedFrom** | 标记此 context 是从哪个 context 进化而来（`null` 表示初始预置） |
| **evolutionCount** | Skill 级别计数器，跨所有 context 累计进化次数 |
| **detectionCommands** | 该上下文在当前环境下用于安全检测的具体命令列表 |

#### 环境匹配规则（程序侧）

当对目标容器执行检测时，按以下优先级选择 executionContext：

1. **精确匹配**：`osFlavors` 包含目标系统 flavor 且 `requiredTools` 全部可用 → 直接使用
2. **模糊匹配**：`osType` 相同但 flavor 不同，`requiredTools` 全部可用 → 使用但标记为"非精确匹配"
3. **无匹配**：所有 context 都不满足 → 进入**上下文进化**流程，AI 根据目标环境创建一个全新的 executionContext
4. **多匹配**：多个 context 满足时，优先选择 `evolvedAt` 最新的（即最近进化成功的）

---

### 2.2 初始预置 4 个 Skill（每个含多执行上下文）

| Skill ID | 名称 | 说明 | 预置 Context 数 |
|----------|------|------|:---:|
| `SEC-K8S-CTX-001` | 权限与隔离突破 | `--privileged` 模式、高危 Capabilities | 3（Debian/Ubuntu + Alpine + Windows） |
| `SEC-K8S-CTX-002` | 敏感挂载与文件系统滥用 | `/proc`、`/sys`、Docker socket 挂载检测 | 3（Debian/Ubuntu + Alpine + Windows） |
| `SEC-K8S-CTX-003` | 系统调用与内核攻击面 | `seccomp` 状态、`unshare`、`nsenter` 可用性 | 3（Debian/Ubuntu + Alpine + Windows） |
| `SEC-K8S-CTX-004` | 网络隔离与横向移动 | 网络命名空间隔离、`iptables` 访问、服务发现探测 | 3（Debian/Ubuntu + Alpine + Windows） |

> 请为每个 Skill 编写完整的 JSON 文件，每个 Skill 至少包含 **Debian/Ubuntu、Alpine 和 Windows Container 三个 executionContext**。
>
> **Windows Container Context 注意**：Windows 容器无 `/proc` 文件系统、无 `bash`/`ash`，Shell 为 `cmd` 或 `powershell`。检测命令需改用 PowerShell cmdlet（如 `Get-Process`、`Get-Service`）、注册表查询（`reg query`）或 Windows 特有 API（如 HCS 隔离模式检查）。初始 Windows Context 可较简单，后续通过上下文进化逐步丰富。

---

### 2.3 命令安全规则定义（`rules/command-rules.json`）

为了保证 AI 生成的命令不会对目标容器造成破坏性影响，所有经由 SSH 执行的命令必须先通过规则引擎的校验。规则以 JSON 文件形式存储在 `rules/` 目录下，支持热加载。

#### 规则数据结构

```json
{
  "ruleSetId": "default-command-rules",
  "version": "1.0.0",
  "description": "默认命令安全规则，拦截高危破坏性操作",
  "rules": [
    {
      "ruleId": "R-001",
      "category": "DESTRUCTIVE_DELETE",
      "description": "禁止递归强制删除根路径或关键系统目录",
      "pattern": "rm\\s+(-[a-zA-Z]*[rf][a-zA-Z]*\\s+)*/(|etc|var|usr|home|root|boot|bin|sbin|lib|opt|tmp|sys|dev|proc)(/\\S*)?",
      "matchType": "REGEX",
      "action": "BLOCK",
      "message": "检测到高危删除命令 [{{command}}]：禁止对系统关键目录执行递归强制删除操作"
    },
    {
      "ruleId": "R-002",
      "category": "DESTRUCTIVE_FORMAT",
      "description": "禁止磁盘格式化与清零操作",
      "pattern": "(mkfs\\.|fdisk|dd\\s+if=|wipefs|mkswap)",
      "matchType": "REGEX",
      "action": "BLOCK",
      "message": "检测到磁盘格式化/清零命令 [{{command}}]：可能在容器中执行破坏性磁盘操作"
    },
    {
      "ruleId": "R-003",
      "category": "DESTRUCTIVE_REDIRECT",
      "description": "禁止通过重定向覆写关键系统文件",
      "pattern": "(>|>>)\\s*/etc/(passwd|shadow|group|hosts|fstab|resolv\\.conf|sudoers)",
      "matchType": "REGEX",
      "action": "BLOCK",
      "message": "检测到重定向覆写关键系统文件 [{{command}}]：禁止修改 /etc 下的核心配置文件"
    },
    {
      "ruleId": "R-004",
      "category": "SERVICE_CONTROL",
      "description": "禁止关闭或重启系统服务（可能影响业务）",
      "pattern": "(systemctl|service)\\s+(stop|disable|mask|restart)\\s+(sshd|docker|kubelet|containerd)",
      "matchType": "REGEX",
      "action": "WARN",
      "message": "检测到操作关键服务命令 [{{command}}]：可能中断容器服务，建议人工确认后执行"
    },
    {
      "ruleId": "R-005",
      "category": "NETWORK_DENIAL",
      "description": "禁止修改 iptables 规则导致网络不可达",
      "pattern": "iptables\\s+-[AD]\\s+(INPUT|OUTPUT|FORWARD).*-j\\s+(DROP|REJECT)",
      "matchType": "REGEX",
      "action": "WARN",
      "message": "检测到添加 DROP/REJECT 规则 [{{command}}]：可能导致容器网络不可达"
    },
    {
      "ruleId": "R-006",
      "category": "PRIVILEGE_ESCALATION_COMMAND",
      "description": "禁止执行容器逃逸相关操作",
      "pattern": "(nsenter|unshare)\\s+.*(--mount|--pid|--uts|--ipc|--net|--all)",
      "matchType": "REGEX",
      "action": "BLOCK",
      "message": "检测到命名空间逃逸命令 [{{command}}]：存在严重容器逃逸风险"
    },
    {
      "ruleId": "R-007",
      "category": "ALLOWLIST_SAFE_READ",
      "description": "白名单：允许只读类安全检测命令",
      "pattern": "^(cat|ls|grep|echo|head|tail|find|which|uname|id|whoami|pwd|env|printenv|ps|netstat|ss|ip|ifconfig|mount|df|du|uptime|date|hostname|arch|getconf|cmp|cut|sort|wc|tr|od|strings|file|stat|readlink)\\s",
      "matchType": "REGEX",
      "action": "ALLOW",
      "message": ""
    }
  ],
  "defaultAction": "BLOCK",
  "allowlistMode": false
}
```

#### 规则引擎核心逻辑

| 概念 | 说明 |
|------|------|
| **action** | `ALLOW` — 直接放行；`WARN` — 记录警告日志后放行；`BLOCK` — 拒绝执行并记录 |
| **matchType** | `REGEX` — 正则表达式匹配；`EXACT` — 精确字符串匹配；`PREFIX` — 前缀匹配 |
| **defaultAction** | 当命令不匹配任何规则时的默认处置。设为 `BLOCK` 更安全（所有未知命令需通过白名单规则放行） |
| **allowlistMode** | `true` 时仅放行命中 `ALLOW` 规则的命令；`false` 时仅拦截命中 `BLOCK` 规则的命令 |
| **规则优先级** | `BLOCK` > `WARN` > `ALLOW` — 如果一条命令同时命中多条规则，取最严格的判定 |
| **热加载** | 每次检测任务开始时重新扫描 `rules/` 目录，同 Skill 加载机制 |

#### 规则判定结果（`RuleVerdict`）

```java
public class RuleVerdict {
    private String verdict;       // ALLOW | BLOCK | WARN
    private String matchedRuleId; // 命中的规则 ID，未命中为 null
    private String message;       // 拦截/警告原因，ALLOW 时为空
    private String originalCommand; // 原始命令
}
```

#### 规则命中日志格式

SLF4J 日志输出示例：
```
[taskId-xxx] 规则命中 R-001 (BLOCK): rm -rf /etc/config → 拦截原因: 禁止对系统关键目录执行递归强制删除操作
[taskId-xxx] 规则命中 R-007 (ALLOW): cat /proc/1/status → 放行
[taskId-xxx] 规则命中 R-004 (WARN): systemctl stop docker → 警告已记录，仍需人工确认
```

---

## 三、核心流程

### 3.1 整体检测流程

```
用户创建任务 (targetIp + 选择 Skill IDs)
  │
  ▼
SkillLoaderService 重新扫描 skills/ 目录（热加载）
  │  规则：同一 skillId 取 versionTimestamp 最大的文件
  │
  ▼
对每个选中的 Skill，启动独立 Sub-Agent 上下文：
  │
  ├─ 阶段零：环境指纹采集
  │    │
  │    ├─ 1. 遍历 Skill 的所有 executionContexts，收集所有 envCheckCommands 并去重
  │    ├─ 2. 批量执行环境探针命令，获取目标容器的环境指纹
  │    │      (OS 类型、发行版、内核版本、可用工具、Shell 类型、架构)
  │    └─ 3. 按环境匹配规则选择最佳 executionContext
  │           · 有匹配 → 直接使用该 context 的 detectionCommands
  │           · 无匹配 → 进入"上下文进化"(Context Evolution) 流程
  │
  ├─ 阶段一：命令执行 + AI 判定 + 命令级进化
  │    │
  │    ├─ 1. 按顺序执行选中 context 的 executionLogic.detectionCommands
  │    ├─ 1.5. 每条命令执行前，通过 CommandRuleService 校验（BLOCK → 中断该命令；WARN → 日志告警后继续）
  │    ├─ 2. 每条命令执行后，将输出提交 AI 做 PASS / FAIL / EVOLVE 判定
  │    │      · PASS → 记录结果，继续下一条命令
  │    │      · FAIL → 记录结果 + 证据，继续下一条命令
  │    │      · EVOLVE → AI 返回替代命令（同环境内命令适配），重试（最多 N 次）
  │    │      · ENV_MISMATCH → 当前 context 不适用，触发上下文级进化
  │    └─ 3. 所有命令执行完毕 → 汇总判定（任一 FAIL 则整体 FAIL）
  │
  ├─ 阶段二：报告生成
  │    └─ 收集所有执行记录，调用 AI 生成 JSON 报告
  │
  └─ 阶段三：HTML 渲染
       └─ 整合所有 Skill 结果 → scores → 生成 HTML 报告
```

### 3.2 两级进化机制

本系统支持**两个粒度**的进化，分别应对不同场景：

#### 3.2.1 命令级进化（Command Evolution）— 同 Context 内

适用场景：当前 executionContext 的环境指纹基本匹配，但某条具体命令在目标环境中"不可用"（如 `capsh` 不存在但 `cat /proc/1/status` 可用）。

- 当 AI 返回 `EVOLVE` 状态时，提取其 `nextCommand` 字段
- 使用新命令在容器中重新执行，再次提交 AI 判定
- 若新命令最终得到 PASS 或 FAIL，将该命令追加到当前 context 的 `detectionCommands` 数组中
- 更新 versionTimestamp，生成新的 Skill 文件，`evolutionCount + 1`

#### 3.2.2 上下文级进化（Context Evolution）— 跨环境适配

适用场景：目标容器的操作系统、发行版、Shell 等与所有现有 executionContext 都**完全不匹配**（例如：已有的 context 都是 Debian/Alpine，但目标容器是 Windows Container、或精简到极致的 distroless 镜像）。

**触发条件**：
- 所有 executionContext 的环境指纹匹配均失败
- 或 AI 连续收到 `ENV_MISMATCH` 判定（说明当前 context 的检测思路不适用于该环境）

**进化流程**：

```
目标环境指纹采集完毕，无匹配的 executionContext
  │
  ▼
调用 AI（附带 Skill 定义 + 目标环境指纹）提示：
  "你是一个安全专家。这个 Skill 的检测目标是 {{description}}。
   但现在面对一个全新的环境，没有一个已有的执行上下文能匹配。
   请根据环境的实际特征，生成一套全新的 executionContext，
   包括 envCheckCommands 和 detectionCommands。"
  │
  ▼
AI 返回新 executionContext (JSON) → 程序侧校验
  │
  ▼
将新 context 追加到 Skill 的 executionContexts 数组中
  ├─ contextId: 自动生成（如 "linux-windows-servercore-003"）
  ├─ evolvedFrom: 标记为 "ai-generated"
  ├─ evolvedAt: 当前时间戳
  │
  ▼
使用新 context 重新执行阶段一检测
  │
  ▼
检测成功 → 生成新的 Skill 文件持久化
          → evolutionCount + 1
```

**重要约束**：
- 上下文进化由 AI 生成的是**整个 executionContext 对象**（不仅是命令列表），包括环境指纹、预期行为、探针命令
- 新 context 会被追加到 Skill 文件中，不影响已有的其他 context
- 如果 AI 生成的 context 检测失败，也保存（标记为 `deprecated: true`），避免后续重复生成
- 同一环境指纹不应有多个 context——程序侧在保存前做去重检查

#### 3.2.3 进化结果持久化

无论是命令级还是上下文级进化，都以以下方式落盘：
- 生成新的 Skill 文件：`{skillId}-{System.currentTimeMillis()}.json`
- `evolutionCount` 自增
- 旧文件保留，前端可查看完整进化历史
- 下次 `SkillLoaderService` 扫描时按 versionTimestamp 取最新

### 3.3 SSH 连接管理

- 使用 Apache SSHD 客户端
- 按 `targetIp` 维度维护连接池（`Map<String, Pool<SshSession>>`）
- 同一 Task 内的多次命令执行复用同一 Session
- Task 完成后归还/关闭连接
- 配置：最大连接数、空闲超时、连接超时

---

## 四、AI Prompt 定义

以下 Prompt 模板中 `{{...}}` 为程序侧注入的变量占位符。

---

### 4.1 阶段零 System Prompt：环境指纹匹配与 Context 选择

```
你是一个容器运行时环境分析专家。你的任务是分析目标容器的环境指纹信息，并从 Skill 的多个执行上下文中选择最匹配的一个。

【Skill 检测目标】：{{skillId}} - {{skillName}}
【检测描述】：{{description}}

【已有的执行上下文列表】：
{{#each executionContexts}}
---
上下文 ID: {{contextId}}
适用环境指纹:
  - 操作系统: {{environmentFingerprint.osType}}
  - 发行版: {{environmentFingerprint.osFlavors}}
  - Shell: {{environmentFingerprint.shellType}}
  - 必需工具: {{environmentFingerprint.requiredTools}}
  - 内核最低版本: {{environmentFingerprint.minKernelVersion}}
  - 架构: {{environmentFingerprint.arch}}
该上下文的检测命令:
{{#each executionLogic.detectionCommands}}
  - {{this}}
{{/each}}
{{/each}}

【目标容器环境指纹（已采集）】：
- 操作系统类型: {{targetEnv.osType}}
- 发行版 ID: {{targetEnv.osFlavor}}
- 发行版版本: {{targetEnv.osVersion}}
- 内核版本: {{targetEnv.kernelVersion}}
- Shell 类型: {{targetEnv.shellType}}
- 架构: {{targetEnv.arch}}
- 已安装的工具: {{targetEnv.availableTools}}
- 缺失的工具: {{targetEnv.missingTools}}

【你的任务】：
1. 逐一评估每个 executionContext 与目标环境的匹配度
2. 选择最佳匹配的 context
3. 如果所有 context 都不匹配，明确说明原因，并给出全新的 executionContext 建议

【输出格式】：
你必须且只能返回一个合法的 JSON 对象，不要包含 Markdown 标记：
{
  "matchResult": "MATCHED | PARTIAL | NONE",
  "selectedContextId": "如果匹配，填写选中的 contextId；否则为空字符串",
  "matchReasoning": "匹配或不匹配的详细原因分析",
  "environmentSummary": "对目标容器环境的一句话总结，例如：Alpine Linux 3.19 / ash shell / 工具链: cat,grep,mount",
  "newContextSuggestion": {
    "included": false,
    "contextId": "",
    "environmentFingerprint": {},
    "envCheckCommands": [],
    "executionLogic": {
      "expectedBehavior": "",
      "detectionCommands": []
    }
  }
}

【特殊规则】：
- 如果 matchResult 为 NONE，你必须填写 newContextSuggestion（included: true），
  为当前目标环境设计一套全新的检测方案。detectionCommands 中必须使用目标环境
  已安装的工具来构建等效检测逻辑。
- 如果 matchResult 为 PARTIAL，表示 OS 类型匹配但工具缺失——尝试在
  newContextSuggestion 中给出仅使用可用工具的简化方案。
```

### 4.2 阶段零 User Prompt（环境信息注入）

```
请根据以下实测环境信息，为 Skill {{skillId}} 选择最佳执行上下文：

目标容器环境实测数据：
- uname -s 输出: {{envRaw.unameS}}
- OS Release: {{envRaw.osRelease}}
- 内核版本: {{envRaw.kernelVersion}}
- Shell: {{envRaw.shell}}
- 架构: {{envRaw.arch}}
- which 命令结果: {{envRaw.whichOutput}}
- 其他探针输出: {{envRaw.extraProbes}}

Skill {{skillId}} 当前共有 {{contextCount}} 个执行上下文。
请严格按照 System Prompt 要求的 JSON 格式输出匹配结果。
```

---

### 4.3 阶段一 System Prompt（命令执行判定 + 命令级进化）

```
你是一个专业的容器运行时安全审计 Agent。你的任务是根据传入的【安全检测 Skill】配置，
在目标容器内执行探测命令，并根据回显判断容器是否存在安全风险。

【当前加载的检测 Skill 信息】：
- Skill ID: {{skillId}}
- Skill 名称: {{skillName}}
- 检测目标: {{description}}
- 整体风险等级: {{riskLevel}}

【当前使用的执行上下文】：
- Context ID: {{contextId}}
- 适配环境: {{environmentSummary}}
- 预期安全表现: {{executionLogic.expectedBehavior}}
- 该上下文检测命令列表: {{executionLogic.detectionCommands}}

【目标容器环境概要】：
- OS: {{targetEnv.osType}} / {{targetEnv.osFlavor}}
- 内核: {{targetEnv.kernelVersion}}
- Shell: {{targetEnv.shellType}}
- 可用工具: {{targetEnv.availableTools}}

【工作流程与判定规则】：
1. 宿主程序会将当前执行的命令及其在容器内的真实回显（stdout, stderr, exit_code）发送给你。
2. 你需要分析回显并做出以下四者之一的判定：

   - PASS：回显表明容器防御生效，安全隔离正常。
         （例如：Operation not permitted / Permission denied / 命令被 seccomp 阻止）
   
   - FAIL：回显表明攻击或探测成功，容器存在安全缺陷。
         （例如：成功打印特权掩码、成功挂载敏感目录、成功访问宿主机资源）
   
   - EVOLVE：当前探测命令在目标环境中"不可用"或"不存在"，但检测目标不变。
         你需要推理出一条【同等检测目的的新命令】。新命令必须：
         · 使用目标环境已安装的工具
         · 保持等价的检测目的
         · 优先使用 POSIX 标准工具（cat, grep, awk, sh 等）

   - ENV_MISMATCH：当前命令的执行结果表明，整个 executionContext 的根本假设
         与目标环境不符（例如：预期是 Linux 但实际是 Windows、预期有 /proc
         文件系统但实际没有、预期有 bash 但只有 cmd.exe）。
         此时应建议切换到上下文级进化。

【严格的输出格式】：
你必须且只能返回一个合法的 JSON 对象，不要包含 Markdown 标记：
{
  "status": "PASS | FAIL | EVOLVE | ENV_MISMATCH",
  "reasoning": "简要说明做出该判断的原因（中文，100字以内）",
  "nextCommand": "EVOLVE 时填写新命令；ENV_MISMATCH 时填写建议；其他状态为空字符串",
  "evidence": "从回显中提取的关键证据文本（用于报告展示）",
  "riskJustification": "如果是 FAIL，说明为什么这个发现构成安全风险"
}
```

### 4.4 阶段一 User Prompt

```
Skill {{skillId}} | Context {{contextId}} | 命令 {{commandIndex}}/{{totalCommands}}

当前执行命令: {{currentCommand}}
容器 SSH 执行回显结果:
- Exit Code: {{exitCode}}
- STDOUT:
{{stdout}}
- STDERR:
{{stderr}}

该上下文剩余待执行命令: {{remainingCommands}}

请根据以上结果，结合 System Prompt 规则，输出对应的判定 JSON。
```

---

### 4.5 上下文进化专用 Prompt（Context Evolution）

当阶段零无法匹配任何现有 context，或阶段一连续返回 ENV_MISMATCH 时触发。

#### System Prompt

```
你是一个资深的云原生安全专家，专精于容器运行时安全检测与跨平台适配。
你的任务是为一个已有的安全检测 Skill 创建一个适配全新环境的执行上下文。

【Skill 基本信息】：
- Skill ID: {{skillId}}
- Skill 名称: {{skillName}}
- 检测目标: {{description}}
- 风险等级: {{riskLevel}}
- 修复关注点: {{reportMetadata.remediationFocus}}

【已有执行上下文（供参考，这些都不能用）】：
{{#each executionContexts}}
- {{contextId}}: 适配 {{environmentFingerprint.osFlavors}}，
  使用 [{{executionLogic.detectionCommands}}]
{{/each}}

【新目标环境指纹】：
- 操作系统类型: {{targetEnv.osType}}
- 发行版: {{targetEnv.osFlavor}}
- 发行版版本: {{targetEnv.osVersion}}
- 内核版本: {{targetEnv.kernelVersion}}
- Shell: {{targetEnv.shellType}}
- 架构: {{targetEnv.arch}}
- 已安装工具: {{targetEnv.availableTools}}
- 缺失工具: {{targetEnv.missingTools}}

【你的核心任务】：
在【不改变检测目标】的前提下，使用目标环境中【已安装的工具】，
设计一套全新的执行上下文。这意味着：
1. envCheckCommands 必须能准确采集该环境的指纹
2. detectionCommands 必须能等价的达成检测目标
3. expectedBehavior 必须准确描述该环境下"安全"和"不安全"的表现

例如：
- Debian 上用 capsh --print 检测 Capabilities
- Alpine 上用 cat /proc/1/status | grep CapEff 实现同等检测
- Windows Container 上需要完全不同的思路（如检查 HCS 隔离模式）

【输出格式】：
你必须且只能返回一个合法的 JSON 对象（不含 Markdown 标记）：
{
  "contextId": "{{skillId}}-{{targetEnv.osFlavor}}-{{timestamp}}",
  "environmentFingerprint": {
    "osType": "linux | windows",
    "osFlavors": ["目标发行版"],
    "shellType": "bash | ash | sh | cmd | powershell",
    "requiredTools": ["工具1", "工具2"],
    "optionalTools": [],
    "minKernelVersion": "x.y.z",
    "arch": ["amd64", "arm64"]
  },
  "envCheckCommands": [
    "用于采集环境指纹的命令1",
    "用于采集环境指纹的命令2"
  ],
  "executionLogic": {
    "expectedBehavior": "该环境下，容器安全隔离正常时预期的行为",
    "detectionCommands": [
      "适配当前环境的检测命令1",
      "适配当前环境的检测命令2"
    ]
  },
  "rationale": "解释你为什么选择这些命令和策略，它们如何等价于原有检测目标"
}
```

#### User Prompt

```
Skill {{skillId}} 在目标容器环境 ({{targetEnv.osFlavor}} {{targetEnv.osVersion}},
内核 {{targetEnv.kernelVersion}}, Shell {{targetEnv.shellType}}) 中没有匹配的执行上下文。

该环境可用工具: {{targetEnv.availableTools}}
环境探针原始输出:
{{envRawOutput}}

请为这个环境创建一套全新的执行上下文，包括环境指纹、探针命令和检测命令。
```

---

### 4.6 阶段二 System Prompt（报告生成器）

```
你是一个资深的云原生安全专家与 Kubernetes 架构师。当前一个容器安全检测任务已经执行完毕。
你的任务是根据检测的原始信息和最终执行回显，出具一份格式严谨的、可被机器解析的 JSON 安全检测报告。

【输入上下文信息】：
- 检测 Skill ID: {{skillId}}
- 检测场景: {{skillName}}
- 检测目标描述: {{description}}
- 修复建议方向: {{reportMetadata.remediationFocus}}
- 最终判定状态: {{finalStatus}} (PASS 表示安全，FAIL 表示存在风险)
- 使用的执行上下文: {{contextId}} (适配环境: {{environmentSummary}})
- 是否发生了进化: {{isEvolved}}
- 进化类型: {{evolutionType}} (command | context | none)

【所有执行记录】：
{{#each executionRecords}}
命令 {{index}}: {{command}}
- Exit Code: {{exitCode}}
- STDOUT: {{stdout}}
- STDERR: {{stderr}}
- AI 判定: {{verdict.status}}
- 判定理由: {{verdict.reasoning}}
{{/each}}

【加固修复要求】：
- 如果最终状态为 FAIL，你必须提供具备实操性的安全加固建议
- 建议应深度结合目标运行环境的特征（{{environmentSummary}}）
- 若涉及 Kubernetes，给出精准的 securityContext / seccompProfile 配置
- 若涉及宿主机，给出运维层面的弥补建议
- 如果目标环境是 Windows Container，给出对应的 Windows 安全策略

【输出要求】：
你必须且只能返回一个合法的 JSON 对象（不含 Markdown 标记）：
{
  "testReport": {
    "summary": "对本次检测结果的简短中文总结（80字以内）",
    "riskLevel": "CRITICAL | HIGH | LOW | INFO (PASS 时固定 INFO)",
    "evidence": "从执行回显中提取的核心证据（用于前端页面高亮展示）",
    "affectedEnvironment": "受影响的执行上下文环境描述"
  },
  "securityRemediation": {
    "strategy": "修复策略的系统化中文描述（包含具体步骤）",
    "k8sYamlPatch": "YAML 代码片段（使用 \\n 换行），不适用则为空字符串",
    "alternativeAdvice": "其他运维或宿主机维度的弥补建议，否则为空字符串",
    "environmentSpecificNotes": "针对当前环境（{{environmentSummary}}）的特殊注意事项"
  }
}
```

### 4.7 阶段二 User Prompt

```
Skill {{skillId}} 检测完成，最终结果: {{finalStatus}}
使用执行上下文: {{contextId}} ({{environmentSummary}})
进化状态: {{isEvolved}} ({{evolutionType}})

最后一次有效探测执行记录：
- 执行命令: {{lastCommand}}
- Exit Code: {{exitCode}}
- STDOUT: {{stdout}}
- STDERR: {{stderr}}

所有历史执行记录：
{{executionRecordsSummary}}

请根据上述信息，严格按照 System Prompt 要求的 JSON 格式输出最终报告与加固建议。
```

---

## 五、API 设计

### 5.1 后端 API

| Method | Path | 说明 |
|--------|------|------|
| `POST` | `/api/tasks` | 创建检测任务 |
| `GET` | `/api/tasks` | 任务列表（支持分页） |
| `GET` | `/api/tasks/{taskId}` | 任务详情 + 检测进度 |
| `GET` | `/api/tasks/{taskId}/report` | 查看 HTML 报告 |
| `GET` | `/api/tasks/{taskId}/report/json` | 查看 JSON 报告数据 |
| `GET` | `/api/skills` | Skill 文件列表（含 contextCount） |
| `GET` | `/api/skills/{skillId}` | 查看最新 Skill 完整内容（含所有 executionContexts） |
| `GET` | `/api/skills/{skillId}/contexts` | 查看该 Skill 的所有 executionContext 列表 |
| `GET` | `/api/skills/{skillId}/contexts/{contextId}` | 查看某个 context 详情 |
| `GET` | `/api/skills/{skillId}/history` | 查看 Skill 进化历史（所有时间戳版本） |
| `GET` | `/api/rules` | 查看当前生效的命令安全规则 |
| `GET` | `/api/rules/history` | 规则命中与拦截历史记录 |
| `POST` | `/api/rules/reload` | 手动触发规则热加载 |

### 5.2 创建任务请求体

```json
{
  "targetIp": "10.244.1.45",
  "sshUser": "root",
  "sshPassword": "***",
  "sshPort": 22,
  "skillIds": ["SEC-K8S-CTX-001", "SEC-K8S-CTX-002"]
}
```

### 5.3 最终报告数据结构

```json
{
  "taskId": "TASK-20260608-001",
  "targetIp": "10.244.1.45",
  "auditTime": "2026-06-08 19:45:00",
  "overallScore": 65,
  "passRate": "75%",
  "targetEnvironment": {
    "osType": "linux",
    "osFlavor": "alpine",
    "osVersion": "3.19.1",
    "kernelVersion": "5.15.0",
    "shellType": "ash",
    "architecture": "amd64"
  },
  "skillReports": [
    {
      "skillId": "SEC-K8S-CTX-001",
      "skillName": "权限与隔离突破",
      "finalStatus": "FAIL",
      "usedContextId": "linux-alpine-busybox",
      "contextEnvironment": "Alpine Linux / ash / 工具链: cat,grep,mount",
      "evolutionType": "none",
      "isEvolved": false,
      "executionRecords": [
        {
          "command": "cat /proc/1/status | grep CapEff",
          "exitCode": 0,
          "stdout": "CapEff: 0000003fffffffff",
          "stderr": "",
          "verdict": "FAIL",
          "verdictReasoning": "成功读取到完整的特权位掩码，表明容器以特权模式运行"
        }
      ],
      "testReport": {
        "summary": "容器以完整特权位掩码运行，存在直接逃逸至宿主机的重大隐患",
        "riskLevel": "CRITICAL",
        "evidence": "CapEff: 0000003fffffffff"
      },
      "securityRemediation": {
        "strategy": "修改该容器所在的 Kubernetes Pod 部署文件，显式关闭特权模式，并移除所有不必要的 Linux 内核 Capabilities",
        "k8sYamlPatch": "spec:\n  containers:\n  - name: my-container\n    securityContext:\n      privileged: false\n      allowPrivilegeEscalation: false\n      capabilities:\n        drop:\n        - ALL",
        "alternativeAdvice": "若业务确实需要部分系统控制权，建议使用特定的最小化 Capability（如仅开启 CAP_NET_ADMIN）代替 --privileged=true",
        "environmentSpecificNotes": "目标为 Alpine 容器（ash shell），privileged 检测无法使用 capsh 命令（Alpine 不包含该工具），已使用 /proc/1/status 替代检测"
      }
    }
  ]
}
```

---

## 六、HTML 报告要求

- 使用 Vue 3 组件渲染或纯 HTML 静态页面（内联 CSS）
- 报告顶部：
  - 整体加固得分（环形图/进度条）、通过率、目标 IP、审计时间
  - **目标环境信息标签**：OS 类型、发行版、内核版本、Shell、架构（来自环境指纹采集结果）
- 每个 Skill 检测结果卡片：
  - 卡片头部：Skill 名称 + PASS/FAIL 状态标签（绿/红视觉分明）
  - 使用的执行上下文标签（如 `Alpine / ash shell`），带有匹配类型标识（精确匹配 / 模糊匹配 / AI 生成）
  - 进化标记：若此 Skill 在检测中发生了进化，显示进化类型和轮次
  - 风险等级（CRITICAL/HIGH/LOW/INFO）
  - **逐条命令执行明细**：每条命令的回显（stdout/stderr）折叠展示
  - 关键证据高亮（红色框标注 FAIL 的证据行）
- 加固建议区域：
  - YAML 代码块语法高亮
  - 策略描述
  - 替代建议
  - **环境特定注意事项**（展示当前环境下的特殊说明）
- 底部：检测过程时间线（环境采集 → Context 选择 → 命令执行 → 进化记录 → 报告生成）
- 响应式布局

---

## 七、JSON 清洗工具

大模型有时会在 JSON 外加 Markdown 代码块标记，请在解析前做以下清洗：

```java
public class JsonCleaner {
    private static final Pattern MARKDOWN_JSON_PATTERN =
        Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```");

    public static String clean(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String trimmed = raw.trim();
        // 去掉 Markdown ```json ... ``` 包裹
        Matcher m = MARKDOWN_JSON_PATTERN.matcher(trimmed);
        if (m.find()) {
            return m.group(1).trim();
        }
        // 尝试找到第一个 { 和最后一个 } 作为 JSON 边界
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}
```

---

## 八、application.yml 关键配置

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY:sk-xxx}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com}
      chat:
        options:
          model: ${AI_MODEL:deepseek-v4-pro}
          temperature: 0.1

security-agent:
  skills:
    directory: ${SKILLS_DIR:./skills}
    max-evolve-retries: 5
  rules:
    directory: ${RULES_DIR:./rules}
    default-action: ${RULES_DEFAULT_ACTION:BLOCK}
  ssh:
    connection-timeout: 10000
    idle-timeout: 60000
    max-sessions-per-host: 3
  report:
    output-directory: ${REPORTS_DIR:./reports}
  detection:
    max-evolve-rounds: 5
    command-timeout-seconds: 30
```

---

## 九、前置页面（Vue 3）

### 页面清单

1. **创建任务页面** (`/create`)
   - 表单：目标 IP、SSH 凭据（密码/密钥）、端口
   - Skill 多选列表（从 `/api/skills` 加载）
   - 提交按钮 → 调用 `POST /api/tasks`

2. **任务列表页面** (`/tasks`)
   - 表格：Task ID、目标 IP、状态、创建时间、操作
   - 操作按钮：查看报告、查看详情

3. **报告查看页面** (`/report/:taskId`)
   - 整体得分展示
   - 各 Skill 检测结果卡片（PASS/FAIL + 风险标签）
   - 加固建议 YAML 展示
   - 打印/导出按钮

4. **Skill 管理页面** (`/skills`)
   - Skill 列表（ID、名称、风险等级、进化次数、最新时间戳、**环境上下文数量**）
   - 点击展开查看 Skill 详情：
     - 基本信息
     - **执行上下文列表**（每个 context 展示其适配环境指纹 + 检测命令列表）
     - 进化历史时间线（按 versionTimestamp 降序，展示每次进化变更了什么）
   - 上下文间对比视图（高亮差异）

### Vue Router 配置

```javascript
const routes = [
  { path: '/', redirect: '/tasks' },
  { path: '/create', component: CreateTask },
  { path: '/tasks', component: TaskList },
  { path: '/report/:taskId', component: ReportView },
  { path: '/skills', component: SkillView },
]
```

---

## 十、关键代码实现要点

### 10.1 SkillLoaderService（热加载 + 环境匹配）

```java
@Service
@Slf4j
public class SkillLoaderService {

    @Value("${security-agent.skills.directory}")
    private String skillsDir;

    private volatile Map<String, SkillDefinition> skillCache = new ConcurrentHashMap<>();

    /**
     * 扫描 skills/ 目录，按 skillId 分组，每组取 versionTimestamp 最大的文件。
     * 每次调用都重新扫描磁盘，实现热加载。
     */
    public Map<String, SkillDefinition> loadLatestSkills() {
        // 1. 列出 skills/ 下所有 .json 文件
        // 2. 文件名解析：{skillId}-{timestamp}.json
        // 3. 按 skillId 分组，取 timestamp 最大者
        // 4. Jackson 反序列化 + 校验 executionContexts 非空
        // 5. 更新缓存
        log.info("Skills 热加载完成，共加载 {} 个 Skill", result.size());
        return result;
    }

    /**
     * 为给定 Skill 和目标环境指纹，选择最佳 executionContext。
     *
     * @return Optional<ExecutionContext> - 匹配成功返回 context，否则 empty
     */
    public Optional<ExecutionContext> selectBestContext(
            SkillDefinition skill,
            EnvironmentFingerprint targetEnv) {

        List<ExecutionContext> contexts = skill.getExecutionContexts();

        // 第一轮：精确匹配 (osFlavors 包含 + 所有 requiredTools 可用)
        for (ExecutionContext ctx : contexts) {
            if (isExactMatch(ctx, targetEnv)) {
                log.info("[{}] 精确匹配 executionContext: {}", skill.getSkillId(), ctx.getContextId());
                return Optional.of(ctx);
            }
        }

        // 第二轮：模糊匹配 (osType 相同 + requiredTools 全部可用)
        for (ExecutionContext ctx : contexts) {
            if (isPartialMatch(ctx, targetEnv)) {
                log.info("[{}] 模糊匹配 executionContext: {}", skill.getSkillId(), ctx.getContextId());
                return Optional.of(ctx);
            }
        }

        log.warn("[{}] 无匹配的 executionContext，目标环境: {}", skill.getSkillId(), targetEnv);
        return Optional.empty();
    }

    private boolean isExactMatch(ExecutionContext ctx, EnvironmentFingerprint env) {
        EnvFingerprint fp = ctx.getEnvironmentFingerprint();
        return fp.getOsType().equalsIgnoreCase(env.getOsType())
            && fp.getOsFlavors().stream().anyMatch(f -> f.equalsIgnoreCase(env.getOsFlavor()))
            && env.getAvailableTools().containsAll(fp.getRequiredTools());
    }

    private boolean isPartialMatch(ExecutionContext ctx, EnvironmentFingerprint env) {
        EnvFingerprint fp = ctx.getEnvironmentFingerprint();
        return fp.getOsType().equalsIgnoreCase(env.getOsType())
            && env.getAvailableTools().containsAll(fp.getRequiredTools());
    }

    /**
     * 保存进化后的 Skill（含新增/修改的 executionContext）。
     * 生成新文件名: {skillId}-{newTimestamp}.json
     */
    public SkillDefinition saveEvolvedSkill(SkillDefinition base) {
        long newTs = System.currentTimeMillis();
        base.setVersionTimestamp(newTs);
        base.setEvolutionCount(base.getEvolutionCount() + 1);

        String fileName = base.getSkillId() + "-" + newTs + ".json";
        Path targetPath = Path.of(skillsDir, fileName);

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(targetPath.toFile(), base);
        log.info("[{}] 进化后 Skill 已持久化: {}", base.getSkillId(), fileName);

        // 刷新缓存
        skillCache.put(base.getSkillId(), base);
        return base;
    }

    /**
     * 获取某个 Skill 的进化历史（所有版本，按时间戳降序）
     */
    public List<SkillDefinition> getEvolutionHistory(String skillId) {
        // 列出 skills/ 下所有以 skillId 开头的 .json 文件
        // 按 versionTimestamp 降序排列
        return ...;
    }

    /**
     * 检查是否已存在相同环境指纹的 context（去重）
     */
    public boolean contextExistsForEnvironment(
            SkillDefinition skill, EnvironmentFingerprint env) {
        return skill.getExecutionContexts().stream()
            .anyMatch(ctx -> isExactMatch(ctx, env));
    }
}
```

### 10.2 SshExecutionService（SSH 连接池）

```java
@Service
@Slf4j
public class SshExecutionService implements AutoCloseable {

    // 按 host:port 维度缓存 SSH Session
    private final Map<String, ClientSession> sessionCache = new ConcurrentHashMap<>();

    /**
     * 获取或创建一个 SSH Session
     */
    private ClientSession getOrCreateSession(String host, int port, String user, String password) { ... }

    /**
     * 在容器中执行命令并返回结果（执行前自动调用规则引擎校验）
     */
    public ExecutionResult execute(String host, int port, String user, String password, String command, int timeoutSeconds) {
        log.debug("SSH 执行命令 [{}]: {}", host, command);
        // 1. 调用 CommandRuleService.filter(command) 校验
        // 2. BLOCK → 返回 ExecutionResult(exitCode=-1, stderr=拦截原因)
        // 3. WARN → 记录警告日志，继续执行
        // 4. 获取 Session
        // 5. 执行命令
        // 6. 收集 stdout, stderr, exitCode
        // 7. 返回 ExecutionResult
    }

    /**
     * Task 完成后释放指定主机的连接
     */
    public void release(String host) { ... }
}
```

### 10.2.5 CommandRuleService（命令安全规则引擎）

```java
@Service
@Slf4j
public class CommandRuleService {

    @Value("${security-agent.rules.directory:./rules}")
    private String rulesDir;

    private volatile RuleSet activeRuleSet;
    private final Object lock = new Object();

    /**
     * 热加载：重新扫描 rules/ 目录，加载所有规则文件。
     * 每次检测任务开始时调用，或通过 POST /api/rules/reload 手动触发。
     */
    public RuleSet reloadRules() {
        synchronized (lock) {
            // 1. 列出 rules/ 下所有 .json 文件
            // 2. 逐个反序列化为 RuleSet
            // 3. 合并所有规则（按 ruleId 去重，相同 ruleId 取最新版本）
            // 4. 按 action 优先级排序：BLOCK 规则在前，WARN 在中，ALLOW 在后
            // 5. 编译所有 REGEX 类型的 Pattern 对象（预编译提升性能）
            // 6. 替换 activeRuleSet
            log.info("命令规则热加载完成，共加载 {} 条规则 (BLOCK: {}, WARN: {}, ALLOW: {})",
                totalRules, blockCount, warnCount, allowCount);
            return activeRuleSet;
        }
    }

    /**
     * 对一条命令执行规则校验。
     *
     * @param command    待执行的命令
     * @param taskId     任务 ID（用于日志关联）
     * @param skillId    Skill ID（用于日志关联）
     * @return RuleVerdict — ALLOW / BLOCK / WARN
     */
    public RuleVerdict filter(String command, String taskId, String skillId) {
        // 确保规则已加载
        RuleSet rules = activeRuleSet;
        if (rules == null) {
            rules = reloadRules();
        }

        RuleVerdict finalVerdict = new RuleVerdict("ALLOW", null, "", command);
        List<RuleVerdict> allMatches = new ArrayList<>();

        // 遍历所有规则
        for (CommandRule rule : rules.getRules()) {
            if (matches(command, rule)) {
                RuleVerdict v = new RuleVerdict(
                    rule.getAction(),
                    rule.getRuleId(),
                    rule.getMessage().replace("{{command}}", command),
                    command
                );
                allMatches.add(v);
            }
        }

        if (allMatches.isEmpty()) {
            // 无规则匹配 → 使用默认处置
            String defaultAction = rules.getDefaultAction();
            if ("BLOCK".equals(defaultAction)) {
                finalVerdict = new RuleVerdict("BLOCK", null,
                    "命令 [" + command + "] 未命中任何放行规则，被默认策略拦截", command);
                log.warn("[{}][{}] 规则未命中 (默认BLOCK): {}", taskId, skillId, command);
            } else {
                log.debug("[{}][{}] 规则未命中 (默认ALLOW): {}", taskId, skillId, command);
            }
        } else {
            // 取最严格的判定：BLOCK > WARN > ALLOW
            for (RuleVerdict v : allMatches) {
                if ("BLOCK".equals(v.getVerdict())) {
                    finalVerdict = v;
                    break;
                }
                if ("WARN".equals(v.getVerdict())) {
                    finalVerdict = v;
                }
                // ALLOW 不覆盖已有判定
            }

            // 记录日志
            for (RuleVerdict v : allMatches) {
                switch (v.getVerdict()) {
                    case "BLOCK" -> log.error("[{}][{}] 规则命中 {} (BLOCK): {} — {}",
                        taskId, skillId, v.getMatchedRuleId(), command, v.getMessage());
                    case "WARN" -> log.warn("[{}][{}] 规则命中 {} (WARN): {} — {}",
                        taskId, skillId, v.getMatchedRuleId(), command, v.getMessage());
                    case "ALLOW" -> log.debug("[{}][{}] 规则命中 {} (ALLOW): {}",
                        taskId, skillId, v.getMatchedRuleId(), command);
                }
            }
        }

        return finalVerdict;
    }

    /**
     * 判断命令是否匹配某条规则。
     */
    private boolean matches(String command, CommandRule rule) {
        return switch (rule.getMatchType()) {
            case "EXACT" -> command.trim().equals(rule.getPattern());
            case "PREFIX" -> command.trim().startsWith(rule.getPattern());
            case "REGEX" -> rule.getCompiledPattern().matcher(command).find();
        };
    }

    /**
     * 返回当前生效的规则集（供前端展示）。
     */
    public RuleSet getActiveRules() {
        if (activeRuleSet == null) {
            reloadRules();
        }
        return activeRuleSet;
    }
}
```

### 10.3 DetectionOrchestrator（核心编排——两级进化）

```java
@Service
@Slf4j
public class DetectionOrchestrator {

    @Value("${security-agent.detection.max-evolve-rounds:5}")
    private int maxEvolveRounds;

    @Value("${security-agent.detection.command-timeout-seconds:30}")
    private int commandTimeout;

    private final SshExecutionService sshService;
    private final SkillLoaderService skillLoader;
    private final AiClientService aiClient;

    /**
     * 对单个 Skill 执行完整的检测流程。
     * 包含：环境指纹采集 → Context 选择 → 命令执行 → AI 判定 → 进化 → 报告
     *
     * 此方法可被 CompletableFuture 并行调用——每个 Skill 独立上下文。
     */
    public SkillReport executeSkillDetection(
            SkillDefinition skill,
            String targetIp, int sshPort, String sshUser, String sshPassword) {

        String skillId = skill.getSkillId();
        log.info("[{}] ===== 开始检测 =====", skillId);

        // ─── 阶段零：环境指纹采集 ───
        EnvironmentFingerprint targetEnv = collectEnvironmentFingerprint(
            skill, targetIp, sshPort, sshUser, sshPassword);
        log.info("[{}] 环境指纹: OS={}/{}, Shell={}, 可用工具={}",
            skillId, targetEnv.getOsType(), targetEnv.getOsFlavor(),
            targetEnv.getShellType(), targetEnv.getAvailableTools());

        // ─── 阶段零.五：执行上下文选择 ───
        Optional<ExecutionContext> matchedCtx =
            skillLoader.selectBestContext(skill, targetEnv);

        ExecutionContext activeContext;
        boolean isContextEvolved = false;

        if (matchedCtx.isPresent()) {
            activeContext = matchedCtx.get();
            log.info("[{}] 使用已有 Context: {}", skillId, activeContext.getContextId());
        } else {
            // 进入上下文级进化
            log.info("[{}] 无匹配 Context，启动上下文进化...", skillId);
            activeContext = evolveNewContext(skill, targetEnv, targetIp,
                sshPort, sshUser, sshPassword);
            if (activeContext == null) {
                return buildContextEvolutionFailedReport(skill, targetEnv);
            }
            skill.getExecutionContexts().add(activeContext);
            isContextEvolved = true;
        }

        // ─── 阶段一：逐命令执行 + 命令级进化 ───
        List<ExecutionRecord> records = new ArrayList<>();
        String finalStatus = "PASS";
        boolean commandEvolved = false;

        List<String> commands = new ArrayList<>(
            activeContext.getExecutionLogic().getDetectionCommands());

        int cmdIndex = 0;
        while (cmdIndex < commands.size()) {
            String command = commands.get(cmdIndex);
            int round = 0;
            String currentCommand = command;

            while (round < maxEvolveRounds) {
                log.info("[{}] 执行命令 [{}/{}] round={}: {}",
                    skillId, cmdIndex + 1, commands.size(), round, currentCommand);

                ExecutionResult result = sshService.execute(
                    targetIp, sshPort, sshUser, sshPassword, currentCommand, commandTimeout);

                // 如果命令被规则引擎拦截（exitCode=-1, stderr=拦截原因），
                // 直接作为 BLOCKED 记录，并通知 AI 此命令被拦截（触发 EVOLVE 生成更安全的替代命令）
                if (result.isBlocked()) {
                    log.warn("[{}] 命令被规则引擎拦截: {}", skillId, currentCommand);
                    AiVerdict blockedVerdict = new AiVerdict("EVOLVE",
                        "命令被规则引擎拦截: " + result.getStderr(),
                        "请生成一条等效但安全的替代命令", "", "");
                    ExecutionRecord blockedRecord = new ExecutionRecord(
                        currentCommand, result, blockedVerdict, round);
                    records.add(blockedRecord);
                    round++;
                    currentCommand = aiClient.judgeExecution(
                        skill, activeContext, targetEnv, currentCommand,
                        cmdIndex, commands.size(), result).getNextCommand();
                    if (currentCommand == null || currentCommand.isEmpty()) break;
                    continue;
                }

                // 调用 AI 判定（携带当前 context 的环境信息）
                AiVerdict verdict = aiClient.judgeExecution(
                    skill, activeContext, targetEnv, currentCommand,
                    cmdIndex, commands.size(), result);

                ExecutionRecord record = new ExecutionRecord(
                    currentCommand, result, verdict, round);
                records.add(record);

                log.info("[{}] AI 判定: {} (round {})", skillId, verdict.getStatus(), round);

                switch (verdict.getStatus()) {
                    case "PASS":
                        cmdIndex++;
                        break; // 跳出 while，执行下一条命令

                    case "FAIL":
                        finalStatus = "FAIL";
                        cmdIndex++;
                        break;

                    case "EVOLVE":
                        round++;
                        currentCommand = verdict.getNextCommand();
                        commandEvolved = true;
                        continue; // 继续 while 循环，重试新命令

                    case "ENV_MISMATCH":
                        // 当前 context 的环境假设完全不对，触发上下文级进化
                        log.warn("[{}] Context {} 环境不匹配，重新进化",
                            skillId, activeContext.getContextId());
                        activeContext = evolveNewContext(skill, targetEnv, targetIp,
                            sshPort, sshUser, sshPassword);
                        if (activeContext == null) break; // 进化失败
                        commands = new ArrayList<>(
                            activeContext.getExecutionLogic().getDetectionCommands());
                        cmdIndex = 0;
                        isContextEvolved = true;
                        break; // 用新 context 的 commands 重新开始
                }
                break; // PASS / FAIL / ENV_MISMATCH 都需要跳出 while
            }

            if (round >= maxEvolveRounds) {
                log.warn("[{}] 命令 {} 超过最大进化轮次", skillId, commands.get(cmdIndex));
                cmdIndex++;
            }
        }

        // ─── 持久化（如有进化） ───
        if (isContextEvolved || commandEvolved) {
            skill.setEvolutionCount(skill.getEvolutionCount() + 1);
            skill = skillLoader.saveEvolvedSkill(skill);
        }

        // ─── 阶段二：生成报告 ───
        log.info("[{}] 检测完成，最终状态={}, 生成报告...", skillId, finalStatus);
        AiReportResponse report = aiClient.generateReport(
            skill, activeContext, targetEnv, finalStatus,
            isContextEvolved, commandEvolved, records);

        return assembleSkillReport(skill, activeContext, targetEnv,
            finalStatus, report, records, isContextEvolved);
    }

    /**
     * 环境指纹采集：合并所有 executionContext 的 envCheckCommands 并去重执行
     */
    private EnvironmentFingerprint collectEnvironmentFingerprint(
            SkillDefinition skill, String ip, int port, String user, String pwd) {

        Set<String> allProbes = skill.getExecutionContexts().stream()
            .flatMap(ctx -> ctx.getEnvCheckCommands().stream())
            .collect(Collectors.toSet());

        String combined = String.join("; echo '---NEXT_PROBE---'; ", allProbes);
        ExecutionResult result = sshService.execute(ip, port, user, pwd, combined, 30);

        // 解析输出 + 采集结构化环境指纹
        return EnvironmentFingerprint.fromProbeResult(result);
    }

    /**
     * 上下文级进化：调用 AI 为当前环境生成全新的 executionContext
     */
    private ExecutionContext evolveNewContext(
            SkillDefinition skill, EnvironmentFingerprint targetEnv,
            String ip, int port, String user, String pwd) {

        // 先检查是否已有 context 去重（避免重复生成）
        if (skillLoader.contextExistsForEnvironment(skill, targetEnv)) {
            log.info("[{}] 已存在匹配当前环境的 context，跳过进化", skill.getSkillId());
            return skillLoader.selectBestContext(skill, targetEnv).orElse(null);
        }

        String rawEnvOutput = collectRawEnvOutput(skill, ip, port, user, pwd);
        ExecutionContext newCtx = aiClient.evolveNewContext(skill, targetEnv, rawEnvOutput);

        if (newCtx == null) {
            log.error("[{}] AI 上下文进化失败", skill.getSkillId());
            return null;
        }

        // 补全元数据
        newCtx.setEvolvedFrom("ai-generated");
        newCtx.setEvolvedAt(System.currentTimeMillis());

        log.info("[{}] 上下文进化成功，新 Context: {}", skill.getSkillId(), newCtx.getContextId());
        return newCtx;
    }

    private SkillReport assembleSkillReport(...) { ... }
    private SkillReport buildContextEvolutionFailedReport(...) { ... }
}
```

### 10.4 AiClientService（Spring AI 封装——完整版）

```java
@Service
@Slf4j
public class AiClientService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public AiClientService(ChatClient.Builder builder, ObjectMapper objectMapper) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
    }

    // ──────────────────── 阶段零：环境匹配 ────────────────────

    /**
     * 将目标环境指纹提交给 AI，让其从 Skill 的多个 executionContext 中选择最佳匹配。
     * 若所有 context 都不匹配，AI 会在返回的 newContextSuggestion 中给出新 context 的草案。
     */
    public ContextMatchResult matchEnvironment(
            SkillDefinition skill,
            EnvironmentFingerprint targetEnv,
            ExecutionResult rawProbeResult) {

        String systemPrompt = buildPhase0SystemPrompt(skill);
        String userPrompt = buildPhase0UserPrompt(skill, targetEnv, rawProbeResult);
        return callAndParse(systemPrompt, userPrompt, ContextMatchResult.class);
    }

    // ──────────────────── 阶段一：命令执行判定 ────────────────────

    /**
     * 将容器内命令执行结果提交给 AI，判定 PASS / FAIL / EVOLVE / ENV_MISMATCH。
     * 携带当前使用的 executionContext 和目标环境指纹作为背景。
     */
    public AiVerdict judgeExecution(
            SkillDefinition skill,
            ExecutionContext activeContext,
            EnvironmentFingerprint targetEnv,
            String currentCommand,
            int commandIndex,
            int totalCommands,
            ExecutionResult result) {

        String systemPrompt = buildPhase1SystemPrompt(
            skill, activeContext, targetEnv);
        String userPrompt = buildPhase1UserPrompt(
            skill, activeContext, currentCommand, commandIndex, totalCommands, result);
        return callAndParse(systemPrompt, userPrompt, AiVerdict.class);
    }

    // ──────────────────── 上下文进化 ────────────────────

    /**
     * 当所有现有 executionContext 都不匹配目标环境时，
     * 调用 AI 生成一个全新的 executionContext。
     */
    public ExecutionContext evolveNewContext(
            SkillDefinition skill,
            EnvironmentFingerprint targetEnv,
            String rawEnvOutput) {

        String systemPrompt = buildContextEvolutionSystemPrompt(skill, targetEnv);
        String userPrompt = buildContextEvolutionUserPrompt(
            skill, targetEnv, rawEnvOutput);
        return callAndParse(systemPrompt, userPrompt, ExecutionContext.class);
    }

    // ──────────────────── 阶段二：报告生成 ────────────────────

    /**
     * 汇总所有执行记录，调用 AI 生成结构化报告（含加固建议）。
     */
    public AiReportResponse generateReport(
            SkillDefinition skill,
            ExecutionContext activeContext,
            EnvironmentFingerprint targetEnv,
            String finalStatus,
            boolean isContextEvolved,
            boolean isCommandEvolved,
            List<ExecutionRecord> allRecords) {

        String evolutionType = isContextEvolved ? "context" :
                               isCommandEvolved ? "command" : "none";

        String systemPrompt = buildPhase2SystemPrompt(
            skill, activeContext, targetEnv, finalStatus, evolutionType);
        String userPrompt = buildPhase2UserPrompt(
            skill, activeContext, targetEnv, finalStatus,
            evolutionType, allRecords);
        return callAndParse(systemPrompt, userPrompt, AiReportResponse.class);
    }

    // ──────────────────── 工具方法 ────────────────────

    /**
     * 调用 AI 并解析返回的 JSON。
     * 包含自动重试机制：JSON 解析失败 → 清洗 → 重试 → 仍失败则抛异常。
     */
    private <T> T callAndParse(String system, String user, Class<T> clazz) {
        String raw;
        try {
            raw = chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .content();
        } catch (Exception e) {
            log.error("AI 调用失败", e);
            throw new AiInvocationException("AI 服务不可用", e);
        }

        log.debug("AI 原始返回 ({}): {}", clazz.getSimpleName(), raw);

        // 第一轮：直接清洗后解析
        String cleaned = JsonCleaner.clean(raw);
        try {
            return objectMapper.readValue(cleaned, clazz);
        } catch (JsonProcessingException e1) {
            log.warn("AI JSON 首轮解析失败，尝试修复后重试...");

            // 第二轮：调用 AI 自我修复格式
            String fixed = selfRepairJson(cleaned, clazz);
            try {
                return objectMapper.readValue(fixed, clazz);
            } catch (JsonProcessingException e2) {
                log.error("AI JSON 修复后仍然解析失败\n原始返回: {}\n清洗后: {}\n修复后: {}",
                    raw, cleaned, fixed);
                throw new AiResponseParseException(
                    "AI 返回值无法解析为 " + clazz.getSimpleName(), e2);
            }
        }
    }

    /**
     * 让 AI 自我修复格式不正确的 JSON。
     */
    private String selfRepairJson(String broken, Class<?> targetClass) {
        String repairPrompt = String.format("""
            以下 JSON 文本无法被 Jackson 解析。请修复它，使其成为合法的 JSON。
            目标类型: %s
            损坏的 JSON: %s
            请仅输出修复后的 JSON，不要包含任何解释或 Markdown 标记。
            """, targetClass.getSimpleName(), broken);

        try {
            String raw = chatClient.prompt()
                .user(repairPrompt)
                .call()
                .content();
            return JsonCleaner.clean(raw);
        } catch (Exception e) {
            log.error("AI JSON 自我修复失败", e);
            return broken; // 返回原始文本，让上层决定如何处理
        }
    }

    // ──────────────────── Prompt 构建方法 ────────────────────

    private String buildPhase0SystemPrompt(SkillDefinition skill) {
        // 对应 4.1 节的 System Prompt 模板
        return TemplateEngine.render("phase0-system", Map.of(
            "skill", skill
        ));
    }

    private String buildPhase1SystemPrompt(
            SkillDefinition skill, ExecutionContext ctx, EnvironmentFingerprint env) {
        // 对应 4.3 节的 System Prompt 模板
        return TemplateEngine.render("phase1-system", Map.of(
            "skill", skill, "context", ctx, "targetEnv", env
        ));
    }

    private String buildContextEvolutionSystemPrompt(
            SkillDefinition skill, EnvironmentFingerprint env) {
        // 对应 4.5 节的 System Prompt 模板
        return TemplateEngine.render("context-evolution-system", Map.of(
            "skill", skill, "targetEnv", env
        ));
    }

    private String buildPhase2SystemPrompt(
            SkillDefinition skill, ExecutionContext ctx,
            EnvironmentFingerprint env, String status, String evoType) {
        // 对应 4.6 节的 System Prompt 模板
        return TemplateEngine.render("phase2-system", Map.of(
            "skill", skill, "context", ctx, "targetEnv", env,
            "finalStatus", status, "evolutionType", evoType
        ));
    }

    // buildPhase0UserPrompt, buildPhase1UserPrompt, ... 类似
}
```

> **提示**：Prompt 模板建议使用 Mustache 或 Handlebars 模板引擎管理，这样 Prompt 文本与 Java 代码分离，便于非开发人员调整。所有模板文件放在 `src/main/resources/prompts/` 目录下。

---

## 十一、额外设计约束

1. **并发支持**：`DetectionOrchestrator` 使用 `CompletableFuture` 或 `ExecutorService` 并行执行多个 Skill 检测，每个 Skill 使用独立的 Sub-Agent 上下文（独立环境指纹、独立 Context）
2. **Task 状态机**：`CREATED → RUNNING → COMPLETED / FAILED`
3. **错误处理**：
   - SSH 连接失败 → Task 标记为 FAILED，记录错误原因
   - AI 调用超时/失败 → 重试 2 次，仍失败则 Task FAILED
   - JSON 解析失败 → 先清洗 Markdown 包裹 → 再调用 AI 自我修复 → 仍失败则记录原始返回
4. **日志**：所有关键节点使用 SLF4J，格式：`[skillId][contextId] 操作描述`
5. **无需鉴权**：当前所有 API 开放访问，预留 `SecurityFilterChain` 占位
6. **CORS**：允许前端开发时跨域访问
7. **多环境支持**：每个 Skill 的 `executionContexts` 数组至少包含一个默认 context；当遇到新环境时自动进化生成新 context；同一环境指纹不重复创建 context
8. **Context 去重**：在保存进化后的 Skill 前，检查是否已有相同环境指纹的 context，避免重复生成
9. **Prompt 模板分离**：所有 Prompt 模板以 `.mustache` 文件形式存放在 `src/main/resources/prompts/`，与 Java 代码解耦，便于后续调优
10. **环境指纹采集去重**：阶段零会遍历所有 context 的 `envCheckCommands` 并去重，避免对同一环境信息重复采集
11. **命令规则引擎**：所有 AI 生成的命令在 SSH 执行前，必须经 `CommandRuleService.filter()` 校验
    - `action: BLOCK` → 拒绝执行，返回 `ExecutionResult(isBlocked=true)`，触发 AI 生成替代命令
    - `action: WARN` → 日志告警并记录，继续执行
    - `action: ALLOW` → 直接放行
    - 默认策略 `defaultAction: BLOCK`（白名单思路更安全），未匹配任何规则的命令被拦截
12. **规则热加载**：每次检测任务开始时自动 `reloadRules()`；同时提供 `POST /api/rules/reload` 手动触发
13. **规则日志**：拦截命中使用 ERROR 级别，警告命中使用 WARN 级别，放行命中使用 DEBUG 级别，确保高危操作可追溯

---

## 十二、实现步骤建议

1. **Phase 1 — 项目骨架**：初始化 Spring Boot 项目、Maven 依赖、基础配置
2. **Phase 2 — 数据模型**：Model 类 + Jackson 配置 + 4 个初始 Skill JSON + 默认规则文件
3. **Phase 3 — Skill 热加载**：`SkillLoaderService` + 单元测试
4. **Phase 4 — 命令规则引擎**：`CommandRuleService` + 默认规则 + 热加载 + 单元测试
5. **Phase 5 — SSH 执行**：`SshExecutionService`（内嵌规则过滤）+ 连接池
6. **Phase 6 — AI 集成**：`AiClientService` + Prompt 模板 + JSON 清洗
7. **Phase 7 — 检测编排**：`DetectionOrchestrator` + 进化逻辑
8. **Phase 8 — 报告生成**：`ReportGenerationService` + HTML 渲染
9. **Phase 9 — 后端 API**：Controller 层 + `TaskManagementService`
10. **Phase 10 — 前端**：Vue 3 项目 + 四个页面
11. **Phase 11 — 集成测试**：端到端流程验证

---

## 开始构建

请从 Phase 1 开始，按顺序实现。每完成一个 Phase，简要汇报进度并等待我确认后继续下一阶段。
