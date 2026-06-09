## 上下文

本项目是一个全新构建的**容器运行时安全审计 Agent**，以 Spring Boot 微服务形态交付。现有生态中，容器安全审计工具（如 kube-bench、falco、trivy）多为静态配置驱动，无法根据目标容器的实际环境（OS 发行版、可用工具、Shell 类型）动态调整检测策略。本系统通过引入 AI 驱动的自进化机制，让安全检测能够跨 Debian、Alpine、Windows Container 等异构环境自适应执行。

**约束**：
- 基于 Java 21 + Spring Boot 3.x，Maven 构建，Spring AI 调用 LLM
- 前端使用 Vue 3（Vite），与后端通过 REST API 通信
- SSH 协议访问目标容器（Apache SSHD），非 kubectl exec
- 无需鉴权（预留 SecurityFilterChain 扩展点）
- Skill 和规则以 JSON 文件存储于本地磁盘，支持热加载

## 目标 / 非目标

**目标：**
- 构建一套完整的容器安全审计系统：任务创建 → 环境指纹采集 → 执行上下文匹配 → 命令规则过滤 → SSH 执行 → AI 判定 → 报告生成
- 支持两级 Skill 自我进化：命令级（同环境命令适配）和上下文级（跨环境全新生成）
- 命令安全规则引擎拦截危险操作（rm -rf、dd、mkfs 等），防止 AI 生成的命令造成破坏
- 提供可视化 Web 界面：任务管理、HTML 报告查看、Skill 进化历史

**非目标：**
- 不作为常驻 DaemonSet 运行（本版为按需审计，不实现持续监控）
- 不替代 kubectl exec（不依赖 Kubernetes API，纯 SSH）
- 不实现多租户隔离
- 不集成 CI/CD 流水线触发

## 决策

### D1: 多环境执行上下文（executionContexts）替代单一 prerequisites

**决策**：Skill 使用 `executionContexts[]` 数组管理多套独立检测方案，每套绑定自己的环境指纹（OS/flavor/tools/kernel/shell/arch）。

**理由**：同一检测目标（如"检测 Capabilities"）在 Debian 上用 `capsh --print`，在 Alpine 上需用 `cat /proc/1/status | grep CapEff`，在 Windows Container 上需完全不同思路。单一 prerequisites 无法覆盖这种跨环境差异。

**替代方案**：
- 方案 A（已否决）：为每个环境单独创建 Skill → 导致 Skill 数量爆炸，同一检测目标分散在多个文件
- 方案 B（已否决）：让 AI 每次实时生成命令 → 不可复现，无法积累经验

### D2: 两级进化机制（命令级 + 上下文级）

**决策**：区分两个进化粒度。
- **命令级**（EVOLVE）：同一 Context 内某条命令不可用，AI 用等效替换（如 `capsh` 不可用 → 改用 `/proc` 解析）
- **上下文级**（ENV_MISMATCH / 无匹配）：整个 Context 的环境假设不成立（如 Linux Context 用于 Windows Container），AI 从零生成新 executionContext

**理由**：避免将小范围适配（缺一个命令）升级为大规模重构（生成全新 Context），降低 AI 调用开销和 Token 消耗。

### D3: 命令规则引擎前置拦截

**决策**：所有 SSH 命令在发送到目标容器前，先经 `CommandRuleService.filter()` 校验。默认策略为 `BLOCK`（白名单思路），仅放行命中 `ALLOW` 规则或触发 `WARN` 后人工确认的命令。

**理由**：AI 可能生成破坏性命令（如 `rm -rf /etc`），不可信。规则白名单思路比黑名单更安全——未知命令不执行，已知危险命令必拦截。

**替代方案**：
- 方案 A（已否决）：完全信任 AI → 风险不可控
- 方案 B（已否决）：仅在前端提示确认 → 无法自动化

### D4: Spring AI + deepseek-v4-pro（可配置）+ Mustache 模板

**决策**：LLM 默认使用 `deepseek-v4-pro`，通过 `AI_MODEL` 环境变量可覆盖为其他兼容 OpenAI 协议的模型。使用 Spring AI 的 `ChatClient` 作为调用抽象层，Prompt 模板使用 Mustache 存放在 `src/main/resources/prompts/`，与 Java 代码解耦。

**理由**：deepseek-v4-pro 在安全审计类 Prompt 上表现优秀，且与 OpenAI 协议兼容无需改造。Spring AI 原生支持 OpenAI 协议，减少样板代码。Mustache 模板使非开发人员也能调整 Prompt 措辞，无需重新编译。当前版本全局统一模型，不按任务类型拆分（后续版本可按阶段一/阶段二拆分不同模型以控制成本）。

**替代方案**：
- 方案 A（已否决）：硬编码 Prompt 字符串 → 难以维护和调优
- 方案 B（已否决）：直接用 OkHttp/RestTemplate 调 API → 重复造轮子

### D5: SSH 连接池按目标 IP 维度复用

**决策**：使用 `Map<String, Pool<ClientSession>>` 按 `host:port` 缓存 SSH Session，同一 Task 内多次命令执行复用同一 Session。

**理由**：一次检测可能对同一容器执行数十条命令，频繁建连会增加 SSH 握手开销和容器侧负载，且可能触发容器的连接频率限制。

**替代方案**：
- 方案 A（已否决）：每次命令新建连接 → 资源浪费
- 方案 B（已否决）：全局单一连接池 → 不同容器使用不同凭据，无法复用

### D6: Jackson JSON 解析 + 双层容错

**决策**：AI 返回的 JSON 先经 `JsonCleaner.clean()` 去 Markdown 包裹，再交 Jackson 解析。解析失败时调用 AI 自我修复（`selfRepairJson`），仍失败则抛异常。

**理由**：LLM 输出不可靠，经常在 JSON 外加 ````json` 标记或遗漏引号。两层兜底大幅降低解析报错率。

### D7: 前端 Vue 3 独立构建，Maven 插件拷贝至 static

**决策**：Vue 3 项目独立于后端，通过 Vite 构建产物，Maven `maven-resources-plugin` 将 `dist/` 拷贝至 `src/main/resources/static/`。

**理由**：前后端独立开发、独立构建，降低耦合。Spring Boot 自动将 `static/` 下的内容作为静态资源服务。

**替代方案**：
- 方案 A（已否决）：Thymeleaf SSR 渲染 → 交互复杂度高，不适合数据密集型页面
- 方案 B（已否决）：前后端分离部署 → 增加运维复杂度（需 Nginx 反向代理）

## 风险 / 权衡

| 风险 | 缓解措施 |
|------|----------|
| AI 返回 JSON 格式不稳定导致检测中断 | 双层容错（Markdown 清洗 + AI 自我修复）；所有原始返回值记录日志 |
| 上下文进化生成的 Context 检测效果差 | 失败 Context 也持久化并标记 `deprecated: true`，避免重复生成；后续可人工修正 |
| SSH 连接池泄露 | 使用 try-with-resources + Task 结束释放回调 + 连接空闲超时（idle-timeout: 60s） |
| 大模型生成的危险命令绕过规则引擎 | 规则白名单思维（defaultAction: BLOCK）+ 正则覆盖核心高危模式 + 后续支持 AI 语义级审查 |
| 并发检测时 Token 消耗过大 | 每个 Skill 使用独立 Sub-Agent 上下文，通过 `max-evolve-rounds` 上限控制 |
| 目标容器环境差异大导致检测不准确 | 环境指纹六维采集（OS/flavor/kernel/shell/arch/tools）+ AI 环境预检 Prompt 明确要求"仅使用已安装工具" |

## 迁移计划

**部署步骤**：
1. 初始化：`java -jar container-security-agent.jar` 启动，自动在当前目录创建 `skills/`、`rules/`、`reports/` 目录
2. 首次运行：加载预置的 4 个 Skill（每个含 Debian + Alpine + Windows Container Context）和默认规则文件
3. 环境变量配置：`OPENAI_API_KEY`、`OPENAI_BASE_URL`、`AI_MODEL`（默认 `deepseek-v4-pro`）等

**回滚**：全新项目，无回滚需求。Skill 文件按时间戳版本化存储，回退到旧 Skill 只需删除最新版本文件后触发热加载。

## 待定问题

1. **Windows Container 支持**：**已决定 — 支持。** 初始预置的 4 个 Skill 每个需额外包含 Windows Container 的 executionContext。Windows Container 无 `/proc` 文件系统，检测逻辑需基于 PowerShell / 注册表 / HCS API，初始 Windows Context 可较简单，后续通过上下文进化逐步完善。
2. **LLM 模型选择**：**已决定 — 默认 `deepseek-v4-pro`，可配置。** 通过环境变量 `AI_MODEL` 覆盖，同时 `application.yml` 中预留 `spring.ai.openai.chat.options.model` 配置项。不区分任务的模型异构（先全局统一模型，后续版本再按任务类型拆分）。
3. **报告格式扩展**：**已决定 — 暂仅支持 HTML。** API 已提供 `/api/tasks/{taskId}/report/json` 端点返回 JSON 数据，后续可基于此扩展 PDF/Markdown 导出，但不纳入当前版本范围。
