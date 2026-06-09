## 1. 项目骨架

- [x] 1.1 初始化 Spring Boot 3.x Maven 项目，配置 Java 21，创建 `container-security-agent/` 目录结构
- [x] 1.2 配置 `pom.xml` 依赖：Spring AI (OpenAI)、Apache SSHD、Jackson、SLF4J/Logback、Mustache、Thymeleaf（可选）
- [x] 1.3 创建 `application.yml`：LLM 模型（默认 `deepseek-v4-pro`，可配）、Skills/Rules/Reports 目录路径、SSH 连接池参数、检测超时与进化为数上限
- [x] 1.4 创建 `AgentApplication.java` 主入口，配置 `AiConfig`、`SshPoolConfig`、`JacksonConfig`、`WebMvcConfig`（CORS）

## 2. 数据模型

- [x] 2.1 创建 `SkillDefinition.java`：skillId、skillName、versionTimestamp、riskLevel、description、evolutionCount、reportMetadata、executionContexts[]
- [x] 2.2 创建 `ExecutionContext.java`：contextId、environmentFingerprint、envCheckCommands、executionLogic、evolvedFrom、evolvedAt、deprecated
- [x] 2.3 创建 `EnvironmentFingerprint.java`：osType、osFlavors、shellType、requiredTools、optionalTools、minKernelVersion、arch（含 `fromProbeResult()` 解析方法）
- [x] 2.4 创建 `ExecutionResult.java`：stdout、stderr、exitCode、isBlocked（规则拦截标记）
- [x] 2.5 创建 `ExecutionRecord.java`：command、ExecutionResult、AiVerdict、round
- [x] 2.6 创建 `DetectionTask.java`：taskId、targetIp、sshUser、sshPassword、sshPort、skillIds、status、创建时间
- [x] 2.7 创建枚举 `TaskStatus.java`：CREATED、RUNNING、COMPLETED、FAILED
- [x] 2.8 创建 `AiVerdict.java`：status（PASS/FAIL/EVOLVE/ENV_MISMATCH）、reasoning、nextCommand、evidence、riskJustification
- [x] 2.9 创建 `ContextMatchResult.java`：matchResult（MATCHED/PARTIAL/NONE）、selectedContextId、matchReasoning、environmentSummary、newContextSuggestion
- [x] 2.10 创建 `AiReportResponse.java`：testReport（summary/riskLevel/evidence/affectedEnvironment）+ securityRemediation（strategy/k8sYamlPatch/alternativeAdvice/environmentSpecificNotes）
- [x] 2.11 创建 `SkillReport.java`：skillId、skillName、finalStatus、usedContextId、contextEnvironment、evolutionType、isEvolved、executionRecords、testReport、securityRemediation
- [x] 2.12 创建 `ReportData.java`：taskId、targetIp、auditTime、overallScore、passRate、targetEnvironment、skillReports[]
- [x] 2.13 创建 `CommandRule.java`：ruleId、category、description、pattern、matchType（REGEX/EXACT/PREFIX）、action（ALLOW/WARN/BLOCK）、message、compiledPattern
- [x] 2.14 创建 `RuleVerdict.java`：verdict、matchedRuleId、message、originalCommand

## 3. 初始数据文件

- [x] 3.1 编写 `skills/SEC-K8S-CTX-001-{timestamp}.json`：权限与隔离突破（3 个 Context：Debian/Ubuntu + Alpine + Windows）
- [x] 3.2 编写 `skills/SEC-K8S-CTX-002-{timestamp}.json`：敏感挂载与文件系统滥用（3 个 Context）
- [x] 3.3 编写 `skills/SEC-K8S-CTX-003-{timestamp}.json`：系统调用与内核攻击面（3 个 Context）
- [x] 3.4 编写 `skills/SEC-K8S-CTX-004-{timestamp}.json`：网络隔离与横向移动（3 个 Context）
- [x] 3.5 编写 `rules/command-rules.json`：默认 7 条规则（R-001 ~ R-007，含 BLOCK/WARN/ALLOW 三类）

## 4. Prompt 模板

- [x] 4.1 创建 `src/main/resources/prompts/phase0-system.mustache`：阶段零环境匹配 System Prompt
- [x] 4.2 创建 `src/main/resources/prompts/phase0-user.mustache`：阶段零 User Prompt
- [x] 4.3 创建 `src/main/resources/prompts/phase1-system.mustache`：阶段一命令判定 System Prompt（含 EVOLVE/ENV_MISMATCH）
- [x] 4.4 创建 `src/main/resources/prompts/phase1-user.mustache`：阶段一 User Prompt
- [x] 4.5 创建 `src/main/resources/prompts/context-evolution-system.mustache`：上下文进化 System Prompt
- [x] 4.6 创建 `src/main/resources/prompts/context-evolution-user.mustache`：上下文进化 User Prompt
- [x] 4.7 创建 `src/main/resources/prompts/phase2-system.mustache`：阶段二报告生成 System Prompt
- [x] 4.8 创建 `src/main/resources/prompts/phase2-user.mustache`：阶段二报告生成 User Prompt

## 5. 工具类

- [x] 5.1 实现 `JsonCleaner.java`：正则去 Markdown 包裹（```` ```json ````）、截取 `{...}` 边界
- [x] 5.2 实现 `DateTimeUtil.java`：Unix 毫秒时间戳 ↔ 格式化日期字符串互转
- [x] 5.3 实现 `PromptTemplateEngine.java`：基于 Mustache 的模板渲染，支持变量注入和 `{{#each}}` 循环

## 6. Skill 热加载

- [x] 6.1 实现 `SkillLoaderService.loadLatestSkills()`：扫描 `skills/` 目录 → 文件名解析 `{skillId}-{timestamp}.json` → 按 skillId 分组取最新 → Jackson 反序列化 → 内存缓存
- [x] 6.2 实现 `SkillLoaderService.selectBestContext()`：精确匹配（osFlavors+requiredTools）> 模糊匹配（osType+requiredTools），跳过 `deprecated: true` 的 Context
- [x] 6.3 实现 `SkillLoaderService.saveEvolvedSkill()`：新文件 `{skillId}-{newTimestamp}.json`，evolutionCount++，刷新缓存
- [x] 6.4 实现 `SkillLoaderService.getEvolutionHistory()`：按 skillId 开头的所有文件，versionTimestamp 降序
- [x] 6.5 实现 `SkillLoaderService.contextExistsForEnvironment()`：检查同环境指纹 Context 是否已存在（去重）

## 7. 命令规则引擎

- [x] 7.1 实现 `CommandRuleService.reloadRules()`：扫描 `rules/` 目录 → 合并规则集 → 按 action 优先级排序 → REGEX 预编译
- [x] 7.2 实现 `CommandRuleService.filter(command, taskId, skillId)`：遍历规则 → matches() 判断 → BLOCK > WARN > ALLOW 取最严 → 未命中按 defaultAction 处置
- [x] 7.3 实现 matches()：支持 REGEX（`Pattern.matcher().find()`）、EXACT（trim 后 equals）、PREFIX（startsWith）三种模式
- [x] 7.4 实现命中日志：BLOCK=ERROR、WARN=WARN、ALLOW=DEBUG，格式含 taskId/skillId/ruleId/command/message
- [x] 7.5 被拦截命令返回 `ExecutionResult(isBlocked=true, stderr=拦截原因)`，不发送到目标容器

## 8. SSH 执行服务

- [x] 8.1 实现 `SshExecutionService`：基于 Apache SSHD，按 `host:port` 维度 `ConcurrentHashMap` 缓存 Session
- [x] 8.2 实现 `getOrCreateSession()`：连接超时 10s、空闲超时 60s、每个 host 最大 3 个 Session
- [x] 8.3 实现 `execute()`：获取 Session → 调用 `CommandRuleService.filter()` 校验 → BLOCK 返回 isBlocked 结果 → WARN/ALLOW 继续执行 → 收集 stdout/stderr/exitCode → 返回 ExecutionResult
- [x] 8.4 实现 `release(host)`：Task 完成后关闭指定主机的所有 Session 并从缓存移除
- [x] 8.5 实现 `AutoCloseable` + `@PreDestroy`：服务关闭时释放所有连接

## 9. AI 调用封装

- [x] 9.1 实现 `AiClientService`：基于 Spring AI `ChatClient`，注入 `ObjectMapper`
- [x] 9.2 实现 `matchEnvironment()`：渲染阶段零 Template → 调用 LLM → 解析 `ContextMatchResult`
- [x] 9.3 实现 `judgeExecution()`：渲染阶段一 Template → 调用 LLM → 解析 `AiVerdict`
- [x] 9.4 实现 `evolveNewContext()`：渲染上下文进化 Template → 调用 LLM → 解析 `ExecutionContext`
- [x] 9.5 实现 `generateReport()`：渲染阶段二 Template → 调用 LLM → 解析 `AiReportResponse`
- [x] 9.6 实现 `callAndParse()`：调用 LLM → 获取原始文本 → `JsonCleaner.clean()` → Jackson 解析 → 失败则 `selfRepairJson()` → 再失败抛 `AiResponseParseException`
- [x] 9.7 实现 `selfRepairJson()`：将损坏 JSON 和目标类型名提交给 AI 修复，返回修复后的 JSON

## 10. 检测编排

- [x] 10.1 实现 `DetectionOrchestrator.executeSkillDetection()`：主流程（环境指纹采集 → Context 选择 → 命令执行+判定+进化 → 持久化 → 报告生成）
- [x] 10.2 实现 `collectEnvironmentFingerprint()`：合并所有 Context 的 envCheckCommands 去重 → SSH 批量执行 → `EnvironmentFingerprint.fromProbeResult()` 解析
- [x] 10.3 实现 Context 选择逻辑：程序侧 `selectBestContext()` → 有则用；无则 `evolveNewContext()` → 生成新 Context → 追加到 Skill
- [x] 10.4 实现命令执行循环：逐条 detectionCommands → 规则过滤 → SSH 执行 → `BLOCKED` 则构造 EVOLVE 替代 → 正常结果则 `judgeExecution()` → PASS/FAIL→下一条 / EVOLVE→重试 / ENV_MISMATCH→上下文进化
- [x] 10.5 实现进化持久化：commandEvolved 或 contextEvolved 时 `saveEvolvedSkill()`
- [x] 10.6 实现阶段二调用：`generateReport()` → 组装 `SkillReport`
- [x] 10.7 实现 `buildContextEvolutionFailedReport()`：上下文进化失败时的特殊报告标记
- [x] 10.8 实现并发执行：`CompletableFuture` + `ExecutorService` 并行处理多个 Skill
- [x] 10.9 实现 `Task` 状态机管理：CREATED → RUNNING → COMPLETED/FAILED

## 11. 报告生成服务

- [x] 11.1 实现 `ReportGenerationService`：收集所有 SkillReport → 计算 overallScore/passRate → 组装 ReportData → Jackson 序列化 JSON → 持久化到 `reports/` 目录
- [x] 11.2 实现 HTML 报告渲染：基于 ReportData 动态生成内联 CSS 的 HTML 页面，包含得分环形图（CSS/SVG）、通过率、目标环境标签、PASS/FAIL 卡片、证据高亮、YAML 代码块、时间线
- [x] 11.3 实现报告 API：`GET /api/tasks/{taskId}/report`（HTML）、`GET /api/tasks/{taskId}/report/json`（JSON）

## 12. 任务管理服务

- [x] 12.1 实现 `TaskManagementService`：taskId 生成（`TASK-{yyyyMMdd}-{seq}`）、任务 CRUD、分页查询
- [x] 12.2 实现 `POST /api/tasks`：接收请求体 → 校验必填字段 → 创建 Task → 异步启动 `DetectionOrchestrator` → 返回 202 + taskId
- [x] 12.3 实现 `GET /api/tasks`：分页返回任务列表
- [x] 12.4 实现 `GET /api/tasks/{taskId}`：返回任务详情与检测进度

## 13. 后端 API - Skill 管理

- [x] 13.1 实现 `SkillController`：`GET /api/skills`（列表含 contextCount）、`GET /api/skills/{skillId}`（完整内容）、`GET /api/skills/{skillId}/contexts`（Context 列表）、`GET /api/skills/{skillId}/contexts/{contextId}`（单个 Context）、`GET /api/skills/{skillId}/history`（进化历史）

## 14. 后端 API - 规则管理

- [x] 14.1 实现 `RuleController`：`GET /api/rules`（当前规则集）、`GET /api/rules/history`（命中历史）、`POST /api/rules/reload`（手动热加载）

## 15. Vue 3 前端项目

- [x] 15.1 初始化 Vue 3 + Vite 项目（`frontend/`），配置 `vue-router`、`axios`
- [x] 15.2 实现 `api/index.js`：封装 axios 实例，baseURL 指向后端，定义所有 API 调用函数
- [x] 15.3 实现 `CreateTask.vue`：IP/SSH 表单 + Skill 多选 + 提交逻辑 + 表单校验
- [x] 15.4 实现 `TaskList.vue`：分页表格、状态标签（彩色）、操作按钮（查看报告/详情）
- [x] 15.5 实现 `ReportView.vue`：得分环形图、通过率、环境标签、Skill 卡片（PASS/FAIL+证据+YAML+建议）、时间线、打印按钮
- [x] 15.6 实现 `SkillView.vue`：Skill 列表（含 contextCount）、详情展开（Context 列表+进化时间线+差异对比）
- [x] 15.7 实现 `StatusBadge.vue`：PASS（绿色）/FAIL（红色）/颜色通用组件
- [x] 15.8 实现 `RiskLevelTag.vue`：CRITICAL（红）/HIGH（橙）/LOW（黄）/INFO（蓝）标签组件
- [x] 15.9 实现 `CommandOutput.vue`：命令输出折叠面板组件（stdout/stderr/exitCode）
- [x] 15.10 配置 `vite.config.js`：dev server proxy 到 Spring Boot 8080
- [x] 15.11 配置 `router/index.js`：5 条路由（`/` → /tasks、/create、/tasks、/report/:taskId、/skills）

## 16. 前后端集成

- [x] 16.1 配置 Maven `maven-resources-plugin`：将 `frontend/dist/` 拷贝至 `src/main/resources/static/`
- [x] 16.2 编写 npm build 脚本集成：`npm run build` → Maven 拷贝 → Spring Boot 打包
- [x] 16.3 验证：启动 Spring Boot → 访问 `http://localhost:8080` → 加载 Vue 前端

## 17. 测试与验证

- [x] 17.1 单元测试 `SkillLoaderService`：热加载、版本选择、环境匹配、进化持久化
- [x] 17.2 单元测试 `CommandRuleService`：BLOCK/WARN/ALLOW 三种判定、REGEX/EXACT/PREFIX 三种匹配、默认策略
- [x] 17.3 单元测试 `JsonCleaner`：Markdown 包裹清洗、JSON 边界截取、空输入处理
- [x] 17.4 单元测试 `AiClientService.callAndParse()`：正常 JSON 解析、清洗后解析、自我修复后解析
- [x] 17.5 集成测试 `DetectionOrchestrator.executeSkillDetection()`：端到端（mock SSH + mock AI）验证 PASS/FAIL/EVOLVE/ENV_MISMATCH 四个分支
- [x] 17.6 集成测试上下文进化：无匹配 Context → AI 生成新 Context → 校验 → 持久化
- [x] 17.7 集成测试报告生成：收集 ExecutionRecords → 生成 HTML 报告 → 验证必填字段完整
- [x] 17.8 确认 `openspec-cn status --change "container-security-agent"` 所有产出物标记为 done
