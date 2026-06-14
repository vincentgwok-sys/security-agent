# 容器安全智能检测 Agent — 项目技术总结

> 项目周期：2026/06/11 — 2026/06/14（持续迭代中）
> 代码仓库：GitHub + gitcode
> 总提交数：49 commits

---

## 一、项目概述

**Container Security Agent** 是一个基于 AI 大模型的容器运行时安全检测平台。它通过在被检测容器内执行安全探测命令，结合 DeepSeek 等大语言模型对执行回显进行智能分析，自动给出 PASS/WARN/FAIL 四位判定，并生成包含风险评估、Kubernetes YAML 修复补丁的结构化安全报告。

核心价值：将传统安全审计从"人工分析回显 + 手动编写报告"升级为"AI 自动判定 + 一键生成报告"，大幅提升容器安全检测的效率和准确性。

---

## 二、技术架构

```
┌──────────────────────────────────────────────────────────────────┐
│                      用户交互层 (Vue 3 SPA)                       │
│  任务创建 │ 任务列表(Auto Refresh) │ 报告查看 │ Skill管理 │ AI日志  │
│       connectionType=local/ssh/kubectl/offline                    │
└──────────────────────────┬───────────────────────────────────────┘
                           │ REST API (/api/*)
┌──────────────────────────▼───────────────────────────────────────┐
│                   Spring Boot 3.4.1 (Java 21)                     │
│                                                                   │
│  DetectionOrchestrator — 三阶段检测编排引擎                         │
│    ├─ Phase 0: 环境指纹采集 (envCheckCommands → fingerprint)       │
│    ├─ Phase 0.5: 上下文匹配/进化 (selectBestContext → evolve)      │
│    ├─ Phase 1: AI 判定循环 (execute → PASS/WARN/FAIL/EVOLVE)       │
│    └─ Phase 2: 报告生成 (AI 汇总 → JSON + HTML 报告)               │
│                                                                   │
│  执行后端分发 (按 connectionType)                                   │
│    ├─ SshExecutionService (SSH 连接模式)                            │
│    ├─ LocalExecutionService (本地进程模式, ProcessBuilder)          │
│    └─ OfflineResultService (线下脚本回放)                           │
└──────────────────────────┬───────────────────────────────────────┘
                           │
         ┌─────────────────┼─────────────────┐
         ▼                 ▼                 ▼
   ┌──────────┐    ┌──────────────┐    ┌──────────────┐
   │ 目标容器   │    │ DeepSeek AI  │    │ K8s Cluster  │
   │ (sh -c)   │    │ (OpenAI 兼容) │    │ (kubectl)    │
   └──────────┘    └──────────────┘    └──────────────┘
```

### 技术栈

| 层级 | 技术选型 |
|------|---------|
| 后端框架 | Spring Boot 3.4.1, Java 21 |
| AI 集成 | Spring AI 1.0.0-M4, OpenAI 兼容协议 (DeepSeek) |
| 前端 | Vue 3.4 + Vite 5.4 + Vue Router 4 + Axios |
| SSH | Apache MINA SSHD 2.13.2 (会话池) |
| 模板引擎 | Mustache 0.9.14 (AI Prompt 模板) |
| 序列化 | Jackson + JSR310 |
| 构建 | Maven (Spring Boot fat JAR, 内嵌前端) |
| 部署 | 跨平台 tar.gz 发布包 + bash 启动脚本 |

### 关键架构决策

1. **前端内嵌后端 JAR**：通过 `maven-resources-plugin` 将 Vue 构建产物复制到 `BOOT-INF/classes/static/`，单进程部署，无需 nginx 反向代理
2. **Skill 体系解耦**：安全检测逻辑与代码分离，以 JSON 文件定义，支持热加载（`POST /api/skills/reload`）
3. **多执行模式**：`connectionType` 抽象层支持 SSH 远程、本地进程、Kubectl 跳板、线下脚本四种执行方式
4. **AI 判定闭环**：Phase 1 中 AI 可返回 EVOLVE（命令进化）和 ENV_MISMATCH（上下文切换），实现动态适配不同容器环境

---

## 三、检测能力矩阵（6 个 Skill, 100+ 条检测命令）

### Skill 全景图

| Skill ID | 名称 | 风险等级 | 检测命令数 | 核心检测维度 |
|----------|------|---------|-----------|-------------|
| SEC-K8S-CTX-001 | 权限与隔离突破 | CRITICAL | 25 | 特权模式、Capabilities、Seccomp、逃逸向量（core_pattern/cgroup/uevent_helper/模块加载）、块设备暴露、NoNewPrivs |
| SEC-K8S-CTX-002 | 敏感挂载与文件系统滥用 | HIGH | 19 | Docker/containerd socket、hostPath 挂载黑名单（CCE 标准）、宿主机敏感路径 bind-mount、cgroup 隔离 |
| SEC-K8S-CTX-003 | 系统调用与内核攻击面 | HIGH | 18 | Seccomp 状态、nsenter/unshare 逃逸测试、AppArmor/SELinux MAC 检测、用户命名空间限制 |
| SEC-K8S-CTX-004 | 网络隔离与信息泄露 | HIGH | 40 | 云元数据端点（AWS/阿里云）可达性、K8s API Server、内网横向移动（网关/子网/路由探测）、ARP 邻居发现、环境变量凭据、iptables 权限 |
| SEC-K8S-CTX-005 | 凭证与密钥发现 | CRITICAL | 27 | SSH 私钥、AWS/GCP 凭证、K8s SA Token、/etc/shadow 可读性、sudo NOPASSWD、Git 硬编码凭据、bash_history 敏感命令、Secret 文件权限 |
| SEC-K8S-CTX-006 | 文件系统与软件安全 | MEDIUM | 23 | SUID/SETGID 二进制、全局可写目录、/tmp noexec、渗透测试工具、编译器/解释器、readOnlyRootFS、系统日志泄露 |

### 检测质量优化

在迭代过程中完成了以下关键改进：

1. **AI 判定模型增加 WARN 状态**：原 AiVerdict 仅支持 PASS/FAIL/EVOLVE/ENV_MISMATCH，新增 WARN 后解决了"强制将 WARN 转 FAIL"的误报问题
2. **建立判定硬规则体系**：每个 Skill 的 expectedBehavior 中明确写入判定矩阵（exit=127=PASS, exit=7=PASS, exit=0+空输出=PASS 等），显著减少 AI 误判
3. **修复 `wget -qO-` 误报 CRITICAL**：发现 `wget -q` 在连接失败时仍返回 exit=0（不可靠），全量替换为 `curl --connect-timeout`
4. **管道 EXIT 码捕获 bug**：`cmd | head -5; echo $?` 中的 `$?` 捕获的是 head 而非原命令——改为 `O=$(cmd); R=$?; echo "$O" | head; echo $R`
5. **命令执行防循环**：增加 `totalCommandExecutions` 上限（`commands.size()*3`），防止 ENV_MISMATCH 触发 cmdIndex=0 无限重跑
6. **上下文匹配优化**：`which` 探头命令统一覆盖 `cat`/`grep` 等基础工具，确保 Debian/Ubuntu 上下文能正确匹配，不降级到 Alpine
7. **集成华为云 CCE 最佳实践**：基于 766 页 CCE 文档，补充了 AppArmor、SETUID、readOnlyRootFS、hostPath 黑名单、Secret 权限 0400 等检测

---

## 四、核心模块设计

### 4.1 DetectionOrchestrator — 检测编排引擎

```
executeDetection(List<SkillDefinition>, DetectionTask)
    │
    ├─ Phase 0: collectEnvironmentFingerprint()
    │     └─ dispatchExecuteRaw(envCheckCommands) → EnvironmentFingerprint
    │
    ├─ Phase 0.5: skillLoader.selectBestContext()
    │     └─ 精确匹配(os+flavor+tools) → 模糊匹配(os+tools) → evolveNewContext()
    │
    ├─ Phase 1: 逐命令执行 + AI 判定循环
    │     └─ while(cmdIndex < commands):
    │          dispatchExecute(command) → ExecutionResult
    │          aiClient.judgeExecution() → AiVerdict {PASS|WARN|FAIL|EVOLVE|ENV_MISMATCH}
    │          ┌─ PASS/WARN → cmdIndex++
    │          ├─ FAIL → finalStatus=FAIL, cmdIndex++
    │          ├─ EVOLVE → currentCommand=nextCommand, round++ (最多 maxEvolveRounds)
    │          └─ ENV_MISMATCH → evolveNewContext() (最多 maxContextEvolutions)
    │       防御: totalCommandExecutions ≤ commands.size()*3
    │
    └─ Phase 2: aiClient.generateReport() → AiReportResponse
         └─ SkillReport {testReport + securityRemediation + k8sYamlPatch}
```

### 4.2 LocalExecutionService — 本地进程执行

```java
@Service
public class LocalExecutionService {
    public ExecutionResult execute(String command, int timeoutSeconds) {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        Process process = pb.start();
        // 异步读取 stdout/stderr
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        // 返回 ExecutionResult {stdout, stderr, exitCode, blocked, connectionError}
    }
}
```

设计要点：
- 直接使用 `sh -c` 执行命令，不依赖 SSH 连接
- `waitFor(timeout)` 超时后 `destroyForcibly()` 防止命令悬挂
- 通过 `connectionType=local` 与 SSH/线下模式统一分发

### 4.3 Skill JSON 数据结构

```json
{
  "skillId": "SEC-K8S-CTX-001",
  "skillName": "权限与隔离突破",
  "riskLevel": "CRITICAL",
  "executionContexts": [{
    "contextId": "linux-debian-ubuntu-standard",
    "environmentFingerprint": {
      "osType": "linux", "osFlavors": ["debian","ubuntu"],
      "shellType": "bash",
      "requiredTools": ["capsh","cat","mount","grep","test"]
    },
    "envCheckCommands": ["uname -s","cat /etc/os-release|grep ID=",...],
    "executionLogic": {
      "expectedBehavior": "【判定硬规则】...",
      "detectionCommands": ["capsh --print", "cat /proc/1/status|grep CapEff",...]
    }
  }, {...}]  // Alpine + Windows 上下文
}
```

设计要点：
- 多上下文适配（Linux Debian/Ubuntu + Alpine + Windows ServerCore），运行时时自动匹配
- `envCheckCommands` 先运行确定环境指纹，`detectionCommands` 再执行安全检测
- `expectedBehavior` 作为 AI 判定指导规则，含 exit 码阈值、WARN/FAIL 分界线

### 4.4 Prompt 模板体系（Mustache）

```
Phase 1 Prompt: phase1-system.mustache + phase1-user.mustache
  └─ AI 角色→容器安全审计 Agent
  └─ 输入→Skill 配置 + 执行上下文 + 命令回显(stdout/stderr/exitCode)
  └─ 输出→AiVerdict {status, reasoning, nextCommand, evidence, riskJustification}

Phase 2 Prompt: phase2-system.mustache + phase2-user.mustache
  └─ AI 角色→云原生安全专家
  └─ 输入→全部执行记录 + finalStatus
  └─ 输出→AiReportResponse {testReport, securityRemediation {strategy, k8sYamlPatch, advice}}
```

关键优化：Phase 1 的 evidence 字段禁止原样粘贴内部命令标记符（如 `ROOTFS_MODE:rw`、`COMPILER_FOUND:python3`），必须翻译为人类可读自然语言。

---

## 五、发布与部署方案

### 5.1 跨平台 Release 包

```
container-security-agent/release/
├── run.sh                                ← 容器一键启动（自动解压+停旧+启动）
├── stop.sh                               ← 停止脚本（lsof/fuser/ss/netstat 四种方式）
├── container-security-agent-backend-1.0.0.tar.gz   ← 后端包 (JAR + 配置 + skills/rules)
└── container-security-agent-frontend-1.0.0.tar.gz  ← 前端包（可选）
```

### 5.2 run.sh 启动流程

```
① 检测 bash 环境（sh/dash → exec bash 重执行）
② 解压 backend tar.gz（每次启动都解压最新）
③ 调用 stop.sh 停止旧进程
④ exec start.sh 启动后端 JAR
```

### 5.3 start.sh JDK 查找优先级

```
--jdk CLI 参数 (最高) → JAVA_HOME 环境变量 → .java_home 文件 → PATH (最低)
```

### 5.4 容器典型使用

```bash
cd release
./run.sh --api-key sk-xxx --jdk /usr/local/share/jdk-21/bin/java
```

---

## 六、关键技术挑战与解决方案

### 挑战 1：AI 退出码误判

| 问题 | 表现 | 根因 | 解决方案 |
|------|------|------|---------|
| wget 退出码不可靠 | CRITICAL 误报 | `wget -q` 连接失败仍返回 exit=0 | 全量替换为 `curl --connect-timeout` |
| 管道误捕获 $? | EXIT 码显示不正确 | `cmd \| head; echo $?` 捕获的是 head 的退出码 | `O=$(cmd); R=$?; echo "$O" \| head; echo $R` |
| exit=127 工具缺失被判 FAIL | 不必要的告警 | AI 不理解""not found"含义 | 全 Skill 统一规则：exit=127=PASS |

### 挑战 2：命令重复执行

| 问题 | 根因 | 解决方案 |
|------|------|---------|
| CTX-006 命令重复 27 次 (18 重复) | ENV_MISMATCH → evolveNewContext → cmdIndex=0 → 全量重跑 | `totalCommandExecutions` 上限 `commands.size()*3` |
| TMP mount 命令重复 12 次 | 同上 + 新上下文命令与旧完全相同 | 新上下文命令与旧相同时不重置 cmdIndex |

### 挑战 3：上下文匹配错误

| 问题 | 根因 | 解决方案 |
|------|------|---------|
| Ubuntu 容器被匹配到 Alpine | Debian 上下文的 `which` 探头未查 `cat`/`grep` → 指纹缺基础工具 → selectBestContext 跳过 Debian | `which` 探头统一覆盖基础工具 |

### 挑战 4：判定规则模糊

| 版本 | 问题 | 改进 |
|------|------|------|
| v1 | expectedBehavior 纯自然语言，AI 凭感觉判 | 增加"【判定硬规则】"前缀 + exit 码阈值表 |
| v2 | 规则太宽泛，仍有误判 | 增加具体示例 (curl exit=6/7/28 → PASS) |
| v3 | WARN 规则无法执行 | AiVerdict 模型新增 WARN 状态 |

---

## 七、项目统计

| 维度 | 数据 |
|------|------|
| 后端代码 | 43 个 Java 文件，~5000行 |
| 前端代码 | 7 个 Vue 组件，~2000行 |
| 检测能力 | 6 个 Skill，100+ 条独立检测命令，3 个操作系统上下文 |
| AI Prompt | 6 个 Mustache 模板，覆盖 Phase 0/1/2 全流程 |
| REST API | 6 个 Controller，20+ 个端点 |
| 发布包 | 跨平台 tar.gz（bash/sh 兼容，自动 JDK 查找） |
| 提交记录 | 49 commits，迭代周期 3 天 |
| 文档 | 4 个 OpenSpec 变更档案 + 详尽 README |

---

## 八、后续规划

1. **WebSocket 实时推送**：当前任务列表通过 5 秒轮询刷新，升级为 WebSocket 可在检测完成时立即推送通知
2. **CI/CD 集成**：提供 GitHub Actions / Jenkins Pipeline 模板，直接嵌入 CI 流程
3. **多容器并发扫描**：当前支持并行 Skill 检测，扩展为并行目标容器批量扫描
4. **告警通知**：FAIL/CRITICAL 检测结果自动推送钉钉/飞书/企业微信
5. **检测策略模板**：预置"生产环境基线""外部开发者沙箱""CI 构建容器"等场景模板
6. **仓库空间优化**：Git LFS 迁移或 Release 页面分发，释放 gitcode 仓库空间（当前 1.2GB/1GB 超限）

---

*项目地址：https://github.com/vincentgwok-sys/security-agent | https://gitcode.com/loki2132121/security-agent*
