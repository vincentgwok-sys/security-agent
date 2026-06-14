# Container Security Agent

基于 AI 的容器运行时安全检测工具。通过 SSH 或 kubectl 跳板机连接目标容器，执行预定义的安全检测命令，利用大模型分析回显并生成结构化的安全报告，附带 Kubernetes 修复建议。

## 架构概览

```
┌──────────────┐     ┌─────────────────────────────────┐     ┌──────────────┐
│  Vue 3 前端   │────▶│  Spring Boot 3.4 (Java 21)       │────▶│  目标容器      │
│  Vite + Axios │     │                                 │     │  (直连 SSH)    │
│  Port 5173    │     │  DetectionOrchestrator           │     └──────────────┘
└──────────────┘     │    ├─ Phase 0: 环境指纹采集        │
                      │    ├─ Phase 1: 命令执行 + AI 判定  │     ┌──────────────┐
                      │    └─ Phase 2: 报告生成            │────▶│  DeepSeek AI  │
                      │                                 │     │  (API 调用)    │
                      │  SkillLoader → skills/*.json     │     └──────────────┘
                      │  RuleEngine  → rules/*.json      │
                      │  AiLogService → logs/*.jsonl     │     ┌──────────────┐
                      │  ReportService → reports/*.json  │     │  K8s Pod      │
                      └─────────────────────────────────┘     │  (通过跳板机)   │
                                                              └──────────────┘
```

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.4.1, Java 21 |
| AI 集成 | Spring AI 1.0.0-M4 (OpenAI 兼容, 默认 DeepSeek) |
| SSH 连接 | Apache SSHD 2.13.2 (会话池, 超时控制) |
| 模板引擎 | Mustache 0.9.14 (AI Prompt 模板) |
| 前端 | Vue 3.4, Vue Router 4.3, Axios 1.7, Vite 5.4 |
| 序列化 | Jackson + JSR310 (JSON 文件存储) |
| 构建 | Maven (Spring Boot fat JAR, 内嵌前端) |

## 快速开始

### 前置要求

- JDK 21
- Maven 3.9+
- Node.js 18+
- DeepSeek API Key（或任意 OpenAI 兼容 API）

### 配置

项目通过**环境变量**管理敏感配置。复制模板文件为本地配置：

```bash
cp src/main/resources/application-example.yml src/main/resources/application.yml
```

本地 `application.yml` 已被 `.gitignore` 排除，不会提交到 Git。

**必填环境变量：**

```bash
# Windows PowerShell
$env:SECURITY_AGENT_AI_API_KEY="your-deepseek-api-key"

# Linux / macOS / Git Bash
export SECURITY_AGENT_AI_API_KEY="your-deepseek-api-key"
```

所有环境变量：

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `SECURITY_AGENT_AI_API_KEY` | **(必填)** | DeepSeek 或 OpenAI 兼容 API Key |
| `SECURITY_AGENT_AI_BASE_URL` | `https://api.deepseek.com` | AI API 地址 |
| `SECURITY_AGENT_AI_MODEL` | `deepseek-v4-pro` | 模型名称 |
| `SERVER_PORT` | `8080` | 后端服务端口 |
| `SKILLS_DIR` | `./skills` | Skill 定义文件目录 |
| `RULES_DIR` | `./rules` | 命令规则文件目录 |
| `RULES_DEFAULT_ACTION` | `BLOCK` | 未命中规则时的默认动作 |
| `REPORTS_DIR` | `./reports` | 报告输出目录 |
| `LOGS_DIR` | `./logs` | AI 交互日志目录 |

### 启动开发环境

**Windows (PowerShell):**

```powershell
.\start-dev.ps1
```

该脚本自动完成：停止旧进程 → 构建后端 → 构建前端 → 启动前后端服务。

**手动启动：**

```bash
# 后端 (Port 8080)
mvn package -DskipTests
java -jar target/container-security-agent-1.0.0-SNAPSHOT.jar

# 前端 (Port 5173, 代理到 8080)
cd frontend
npm install
npm run dev
```

**停止服务：**

```powershell
.\stop-dev.ps1
```

### 运行 Release 包（推荐，无需构建环境）

项目已提供预构建的 Release 包（`tar.gz`），解压即用，无需安装 Maven、Node.js 等构建工具。

**包位置：**

```
container-security-agent/release/
├── container-security-agent-backend-1.0.0.tar.gz   ← 后端服务
└── container-security-agent-frontend-1.0.0.tar.gz   ← 前端（可选）
```

#### 后端包

环境要求：**JDK 21+**

```bash
# 1. 解压
tar xzf container-security-agent-backend-1.0.0.tar.gz
cd container-security-agent-backend-1.0.0

# 2. 首次运行（自动生成 application.yml）
# Windows: 双击 start.bat
# Linux/Mac:
./start.sh

# 3. 编辑 application.yml，填入 API Key
#    也可以设置环境变量：export SECURITY_AGENT_AI_API_KEY="sk-xxx"

# 4. 再次运行启动脚本
./start.sh
# → 浏览器打开 http://localhost:8080
```

**JDK 多版本切换：**

如果系统安装了多个 JDK，启动脚本按以下优先级查找 Java 21+：

| 优先级 | 方式 | 示例 |
|--------|------|------|
| 1 | `JAVA_HOME` 环境变量 | `export JAVA_HOME=/usr/lib/jvm/jdk-21` |
| 2 | `.java_home` 本地文件 | `echo /usr/lib/jvm/jdk-21 > .java_home` |
| 3 | `PATH` 中的 `java` | 自动兜底 |

#### 前端包（可选）

后端 JAR 已内嵌前端，大多数情况无需单独使用前端包。如需独立部署：

环境要求：**Python 3**

```bash
tar xzf container-security-agent-frontend-1.0.0.tar.gz
cd container-security-agent-frontend-1.0.0

# Windows: 双击 start.bat
# Linux/Mac:
./start.sh
# → 浏览器打开 http://localhost:5173
```

前端通过 `/api/*` 代理或在同域下访问后端（需配置反向代理或 CORS）。

#### 自定义端口

编辑 `application.yml`，修改 `server.port`，或设置环境变量 `SERVER_PORT=9090`。

### 访问

- 前端界面：`http://localhost:5173`
- 后端 API：`http://localhost:8080/api`

## 功能

### 安全检测

预置 4 个检测 Skill，覆盖容器运行时关键安全风险：

| Skill | 风险等级 | 检测内容 |
|-------|---------|---------|
| SEC-K8S-CTX-001 | **CRITICAL** | 特权模式、CAP_SYS_ADMIN、seccomp、挂载操作 |
| SEC-K8S-CTX-002 | **HIGH** | Docker socket、宿主机文件系统、敏感目录挂载 |
| SEC-K8S-CTX-003 | **HIGH** | Seccomp 策略、nsenter/unshare 逃逸、NoNewPrivs |
| SEC-K8S-CTX-004 | **HIGH** | iptables 修改、集群 API 访问、云元数据端点、ARP 操纵 |

每个 Skill 内置多套执行上下文（Debian/Ubuntu、Alpine、Windows ServerCore），运行时自动匹配。无匹配时 AI 自动生成适配上下文。

### AI 驱动分析

- 命令回显由 DeepSeek AI 判定为 PASS / FAIL / EVOLVE / ENV_MISMATCH
- 命令不适用时 AI 自动推荐等价的替代命令
- 每个命令的执行上下文可动态进化，适配不同容器环境
- 最终生成含风险等级、证据、K8s YAML 修复建议的结构化报告

### 连接方式

- **直连 SSH**：直接 SSH 到目标容器
- **Kubectl 跳板机**：SSH 到跳板机 → 浏览并选择 Pod → 通过 `kubectl exec` 执行检测
- **线下执行**：下载 Python 脚本 → 在隔离容器中离线执行 → 上传结果 ZIP → 平台回放分析

### 命令规则引擎

`rules/command-rules.json` 定义可执行命令的安全策略：
- **BLOCK**：禁止执行（如 `rm -rf`、`iptables -A`）
- **WARN**：告警通过（如 `iptables -L`、`nsenter`）
- **ALLOW**：允许执行（如 `cat`、`echo`）

### 任务管理

- 创建任务时自动选择所有 Skill（可手动调整）
- 对 RUNNING/CREATED 任务支持**终止**（取消异步执行 + 关闭 SSH 会话）
- 对 INTERRUPTED/FAILED 任务支持**重试**（使用最新 Skill 和规则重新检测），每个任务仅允许重试一次
- 对已完成任务支持**删除**（仅删除任务元数据，保留日志和报告）
- 服务重启后未完成的任务自动标记为 INTERRUPTED

### AI 交互日志

`/logs/:taskId` 页面对每次 AI 调用提供完整审计追踪：
- System Prompt 全文
- User Prompt 全文
- 原始响应 + 清洗后 JSON 对比
- 解析结果、目标类型、调用耗时

### 报告

检测完成后生成：
- **JSON 报告**：结构化数据，含总分、通过率、每个 Skill 的详细结果
- **HTML 报告**：可视化仪表盘，环形评分图、时间线、Skill 卡片、修复建议块

## API 端点

### 任务

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/tasks` | 创建检测任务 |
| GET | `/api/tasks?page=0&size=20` | 分页查询任务列表 |
| GET | `/api/tasks/{taskId}` | 查询单个任务 |
| POST | `/api/tasks/{taskId}/cancel` | 终止运行中的任务 |
| DELETE | `/api/tasks/{taskId}` | 删除非运行中任务（保留日志和报告） |
| GET | `/api/tasks/{taskId}/script/download?token={token}` | 下载线下执行脚本 |
| POST | `/api/tasks/{taskId}/results/upload` | 上传线下执行结果 ZIP |
| GET | `/api/tasks/{taskId}/results/download` | 下载原始上传 ZIP 文件 |

### 报告

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/tasks/{taskId}/report` | 获取 HTML 报告 |
| GET | `/api/tasks/{taskId}/report/json` | 获取 JSON 报告 |

### Skill 管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/skills` | 列出所有 Skill |
| GET | `/api/skills/{skillId}` | 查看 Skill 详情 |
| GET | `/api/skills/{skillId}/history` | 查看进化历史 |
| POST | `/api/skills/reload` | 热加载 Skill 文件 |

### 规则管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/rules` | 查看活跃规则 |
| POST | `/api/rules/reload` | 热加载规则文件 |

### Kubectl跳板机

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/kubectl/pods` | 通过跳板机获取 K8s Pod 列表 |

### AI 日志

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/logs/ai?taskId=xxx&page=0&size=50` | 查询 AI 交互日志 |

## 配置参考

所有敏感配置通过环境变量注入。参见上方「配置」章节的完整环境变量表。

```yaml
# application.yml 中的非敏感配置（本地覆盖时可用）
security-agent:
  skills:
    directory: ${SKILLS_DIR:./skills}
  rules:
    directory: ${RULES_DIR:./rules}
    default-action: ${RULES_DEFAULT_ACTION:BLOCK}   # BLOCK | WARN | ALLOW
  ssh:
    connection-timeout: 10000                        # 毫秒
    idle-timeout: 60000                              # 毫秒
    max-sessions-per-host: 3
  report:
    output-directory: ${REPORTS_DIR:./reports}
  logs:
    directory: ${LOGS_DIR:./logs}
  detection:
    max-evolve-rounds: 5                             # 命令进化最大轮次
    max-context-evolutions: 3                        # 上下文进化最大次数
    command-timeout-seconds: 30
```

## 目录结构

```
container-security-agent/
├── frontend/                  # Vue 3 SPA
│   └── src/
│       ├── api/               # Axios API 客户端
│       ├── components/        # 可复用组件
│       ├── router/            # Vue Router 配置
│       └── views/             # 页面组件
├── src/main/
│   ├── java/com/security/agent/
│   │   ├── config/            # Spring 配置 + AI/SSH Beans
│   │   ├── controller/        # REST 控制器 (6个)
│   │   ├── model/             # 数据模型 (16个)
│   │   ├── service/           # 核心服务 (9个)
│   │   └── util/              # 工具类 (3个)
│   └── resources/
│       ├── application.yml    # 配置
│       ├── prompts/           # AI Prompt 模板 (Mustache)
│       └── static/            # 构建后的前端 (构建时自动复制)
├── skills/                    # Skill 定义 JSON 文件
├── rules/                     # 命令规则 JSON 文件
├── reports/                   # 任务和报告输出 (运行时生成)
├── logs/                      # AI 交互日志 (运行时生成)
├── start-dev.ps1              # 开发环境启动脚本
├── stop-dev.ps1               # 开发环境停止脚本
└── pom.xml                    # Maven 构建配置
```

## 核心数据流

```
用户创建任务
  → POST /api/tasks { targetIp, sshUser, sshPassword, skillIds }
    → TaskManagementService 生成唯一 taskId，持久化
    → CompletableFuture.runAsync 异步启动检测
      → Phase 0: SSH 采集环境指纹 → EnvironmentFingerprint
      → Phase 0.5: SkillLoader 匹配/进化 ExecutionContext
      → Phase 1: 逐条执行 detectionCommands
          → SSH 执行命令 → 规则引擎过滤
          → AI 判定 (PASS/FAIL/EVOLVE/ENV_MISMATCH)
          → EVOLVE: 重新尝试替代命令
          → ENV_MISMATCH: 重新进化上下文
      → Phase 2: AI 生成报告 → ReportData → 持久化 JSON + HTML
    → 状态更新为 COMPLETED 或 FAILED
```

## 许可证

MIT
