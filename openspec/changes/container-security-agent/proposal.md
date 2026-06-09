## 为什么

容器化部署已成为标准实践，但容器的安全配置（特权模式、Capabilities、挂载点、网络策略等）参差不齐，存在大量逃逸风险和横向移动隐患。安全团队缺乏一套**智能化的容器安全审计工具**，能够：自动适配不同容器环境（Debian/Alpine/Windows Container 等），通过 AI 驱动执行探测命令并判定风险，支持检测策略的自我进化。本变更构建一套完整的容器运行时安全审计 Agent，以微服务形态交付。

## 变更内容

- 新增基于 Spring Boot 的容器安全审计微服务（`container-security-agent`），包含完整后端 API 和 Vue 3 前端
- 支持以 Skill JSON 文件形式定义安全检测场景，每个 Skill 可包含多个环境执行上下文（executionContexts）
- 实现 AI 驱动的检测流程：环境指纹采集 → 执行上下文匹配 → 命令规则过滤 → 命令执行 → AI 判定（PASS/FAIL/EVOLVE/ENV_MISMATCH）→ 报告生成。所有经由 SSH 执行的命令必须经过规则引擎过滤，拦截危险操作
- 实现两级 Skill 自我进化机制：命令级进化（同环境内命令适配）和上下文级进化（跨环境全新生成执行方案）
- 提供 Web 管理页面：创建检测任务、任务列表、HTML 报告查看、Skill 管理与进化历史

## 功能 (Capabilities)

### 新增功能
- `skill-management`: Skill 文件的磁盘热加载、多环境执行上下文（executionContext）匹配、进化后的 Skill 持久化、版本历史追踪
- `command-rules`: 命令执行安全规则引擎，在 SSH 执行前对 AI 生成的命令进行白名单/黑名单校验，拦截危险命令（如 rm -rf、dd、mkfs、fdisk 等破坏性操作），支持规则的热加载与自定义配置，规则文件以 JSON 格式存储在 `rules/` 目录下
- `security-detection`: 通过 SSH 连接池访问目标容器，执行环境探针命令采集指纹，按匹配规则选择最佳 executionContext，经规则引擎过滤后逐条执行检测命令并提交 AI 判定
- `skill-evolution`: 命令级进化（EVOLVE 时 AI 推理替代命令并重试）和上下文级进化（ENV_MISMATCH 或无匹配时 AI 生成全新 executionContext），进化结果自动去重持久化
- `report-generation`: 汇总所有执行记录，AI 生成结构化安全报告（风险等级、关键证据、加固建议含 K8s YAML 补丁），渲染为 HTML 页面，包含得分、通过率、环境信息、逐命令明细
- `management-ui`: Vue 3 前端，包含任务创建页、任务列表页、报告查看页、Skill 管理页（含执行上下文列表与进化时间线）

### 修改功能
<!-- 全新项目，无现有功能修改 -->

## 影响

- **代码**：全新项目，不影响已有代码。在 `d:\project\security\` 下新建 `container-security-agent/` 目录
- **技术栈**：Java 21 + Spring Boot 3.x + Spring AI（默认模型 deepseek-v4-pro，可配置）+ Apache SSHD + Jackson + Vue 3 + Vite
- **运行依赖**：需要 OpenAI 兼容 API（或兼容的 LLM 服务）、目标容器 SSH 访问凭据
- **运行环境**：JVM 21+，需外挂 `skills/`、`rules/` 和 `reports/` 目录用于 Skill 文件、命令安全规则和报告持久化
