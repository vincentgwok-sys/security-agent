## 目的

管理界面是一个 Vue 3 单页应用，提供任务创建、任务列表、报告查看和 Skill 管理功能。通过 Vite 构建，与 Spring Boot 后端通过 REST API 通信。

## 需求

### 需求: 任务创建页面

系统必须提供前端页面让用户创建检测任务。页面路由为 `/create`。

#### 场景: 填写表单并提交

- **当** 用户访问 `/create` 页面，填写目标 IP、SSH 用户、SSH 密码（或密钥）、端口，从 Skill 多选列表中选择若干 Skill，点击"创建任务"
- **那么** 前端调用 `POST /api/tasks`，成功后跳转到任务列表页

#### 场景: Skill 列表从服务端加载

- **当** 用户访问创建页面
- **那么** 前端调用 `GET /api/skills`，展示每个 Skill 的 skillId、skillName、riskLevel、contextCount，支持多选

#### 场景: 表单校验

- **当** 目标 IP 为空或格式不合法，或未选择任何 Skill
- **那么** 提交按钮禁用，页面显示校验错误提示

### 需求: 任务列表页面

系统必须提供任务列表页面，展示所有检测任务。页面路由为 `/tasks`（同时也是根路径 `/` 的重定向目标）。

#### 场景: 分页展示任务

- **当** 用户访问 `/tasks`
- **那么** 前端调用 `GET /api/tasks?page=0&size=20`，以表格形式展示 taskId、targetIp、状态（CREATED/RUNNING/COMPLETED/FAILED 带颜色标签）、创建时间、操作列

#### 场景: 操作按钮

- **当** 任务状态为 COMPLETED
- **那么** 操作列显示"查看报告"按钮，点击跳转到 `/report/{taskId}`

#### 场景: 运行中任务

- **当** 任务状态为 RUNNING
- **那么** 状态标签显示为动画样式（加载中），操作列显示"查看详情"按钮

### 需求: 报告查看页面

系统必须提供检测报告查看页面。页面路由为 `/report/:taskId`。

#### 场景: 加载报告数据

- **当** 用户访问 `/report/{taskId}`
- **那么** 前端调用 `GET /api/tasks/{taskId}/report/json`，获取 ReportData 并渲染

#### 场景: 报告顶部展示

- **当** ReportData 加载完毕
- **那么** 页面顶部展示：整体加固得分环形图/进度条、通过率百分比、目标 IP、审计时间、目标环境信息标签组（OS 类型、发行版、内核版本、Shell、架构）

#### 场景: Skill 检测结果卡片

- **当** 渲染 Skill 检测结果
- **那么** 每个 Skill 渲染为独立卡片：卡片头显示 Skill 名称 + PASS（绿色）/FAIL（红色）状态标签 + 使用的 Context 匹配类型标签 + 进化标记（如有）；卡片体包含风险等级标签、逐条命令执行明细（折叠面板）、FAIL 证据红色高亮框

#### 场景: 加固建议展示

- **当** Skill 为 FAIL 状态且 securityRemediation 非空
- **那么** 卡片底部展示：修复策略描述文本、YAML 代码块（语法高亮）、替代建议文本、环境特定注意事项

#### 场景: 检测过程时间线

- **当** 报告页面渲染
- **那么** 底部展示时间线条目：环境指纹采集 → Context 选择（含 matchType）→ 命令执行（含 round 信息）→ 进化记录（如有）→ 报告生成

#### 场景: 打印/导出

- **当** 用户点击打印按钮
- **那么** 触发浏览器打印功能（`window.print()`），打印样式优化（隐藏导航、按钮等）

### 需求: Skill 管理页面

系统必须提供 Skill 管理页面，展示所有 Skill 及其执行上下文和进化历史。页面路由为 `/skills`。

#### 场景: Skill 列表展示

- **当** 用户访问 `/skills`
- **那么** 前端调用 `GET /api/skills`，展示列表：skillId、skillName、riskLevel、evolutionCount、versionTimestamp、contextCount

#### 场景: Skill 详情展开

- **当** 用户点击某个 Skill 行
- **那么** 展开详情面板：基本信息（description、reportMetadata）、执行上下文列表（每个 context 显示 contextId、environmentFingerprint 标签、detectionCommands 列表、evolvedFrom/evolvedAt）

#### 场景: 进化历史时间线

- **当** 用户查看 Skill 详情中的"进化历史"标签
- **那么** 前端调用 `GET /api/skills/{skillId}/history`，以时间线形式展示所有版本，每个条目显示 versionTimestamp（格式化日期）、evolutionCount、版本间 executionContexts 的差异对比（新增/删除/修改了哪些 context）

#### 场景: Context 间对比

- **当** Skill 有多个 executionContext
- **那么** 提供"对比视图"按钮，并排展示两个 Context 的差异（environmentFingerprint 字段差异、detectionCommands 差异）

### 需求: Vue Router 配置

前端必须使用 Vue Router 管理单页应用路由。

#### 场景: 路由表

- **当** 应用初始化
- **那么** Router 配置以下路由：
  - `/` → 重定向到 `/tasks`
  - `/create` → CreateTask 组件
  - `/tasks` → TaskList 组件
  - `/report/:taskId` → ReportView 组件
  - `/skills` → SkillView 组件

### 需求: 无需鉴权

当前版本所有 API 和页面不要求用户认证。系统必须预留 Spring Security `SecurityFilterChain` 配置占位，便于未来接入认证。

#### 场景: 所有页面可直接访问

- **当** 用户直接访问任意路由
- **那么** 页面正常加载，无需登录或 Token

#### 场景: CORS 支持

- **当** 前端开发时在 Vite dev server（默认端口 5173）发起 API 请求到 Spring Boot（默认端口 8080）
- **那么** 请求被 CORS 配置放行，不被浏览器拦截
