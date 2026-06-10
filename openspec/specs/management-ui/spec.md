## 目的

管理界面是一个 Vue 3 单页应用，提供任务创建、任务列表、报告查看和 Skill 管理功能。通过 Vite 构建，与 Spring Boot 后端通过 REST API 通信。

## 需求

### 需求: 任务创建页面

系统必须提供前端页面让用户创建检测任务。页面路由为 `/create`。

#### 场景: 填写表单并提交

- **当** 用户访问 `/create` 页面，选择连接方式（直连 SSH / Kubectl 跳板机 / 线下执行），填写对应字段（SSH 模式填写目标 IP、用户、密码、端口；线下模式仅选择 Skill），点击"创建任务"
- **那么** 前端调用 `POST /api/tasks`，成功后根据连接方式展示不同结果：SSH/Kubectl 模式跳转到任务列表页；线下模式显示脚本下载按钮

#### 场景: Skill 列表从服务端加载

- **当** 用户访问创建页面
- **那么** 前端调用 `GET /api/skills`，展示每个 Skill 的 skillId、skillName、riskLevel、contextCount，支持多选

#### 场景: 表单校验

- **当** SSH/Kubectl 模式下目标 IP 为空或格式不合法，或未选择任何 Skill；线下模式下未选择任何 Skill
- **那么** 提交按钮禁用，页面显示校验错误提示

### 需求: 任务列表页面

系统必须提供任务列表页面，展示所有检测任务。页面路由为 `/tasks`（同时也是根路径 `/` 的重定向目标）。

#### 场景: 分页展示任务

- **当** 用户访问 `/tasks`
- **那么** 前端调用 `GET /api/tasks?page=0&size=20`，以表格形式展示 taskId、连接方式图标（SSH🔗/Kubectl⎈/Offline📥）、targetIp（线下任务显示"线下执行"）、状态（含线下特有标签：等待下载/等待执行/已上传/分析中）、创建时间、操作列

#### 场景: 操作按钮

- **当** 任务状态为 COMPLETED
- **那么** 操作列显示"查看报告"按钮，点击跳转到 `/report/{taskId}`

#### 场景: 运行中任务

- **当** 任务状态为 RUNNING 或 ANALYZING
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

### 需求: 任务创建页线下模式入口

前端任务创建页（`/create`）必须新增「线下执行」连接方式选项。

#### 场景: 选择线下执行模式

- **当** 用户在连接方式中选择「📥 线下执行」
- **那么** 页面隐藏 SSH 配置区域（目标 IP、端口、用户名、密码），隐藏 Kubectl 跳板机选项，Skill 多选列表保持可见

#### 场景: 线下模式提交无需 SSH 凭据

- **当** 用户选择线下执行模式并点击"创建任务"
- **那么** 前端调用 `POST /api/tasks`，请求体中 `connectionType` 为 `"offline"`，`targetIp`、`sshUser`、`sshPassword` 可为空字符串

### 需求: 脚本下载区域

线下任务创建成功后，创建页面必须展示脚本下载区域。

#### 场景: 创建成功后展示下载按钮

- **当** 线下任务创建成功（API 返回 202）
- **那么** 页面展示成功消息、任务 ID、脚本下载按钮和 token 信息（仅首次可见），提示用户"请下载脚本并在目标容器中执行"

#### 场景: 下载脚本

- **当** 用户点击下载按钮
- **那么** 前端调用 `GET /api/tasks/{taskId}/script/download?token={token}`，触发浏览器文件下载

#### 场景: token 仅首次可见

- **当** 页面刷新后
- **那么** 下载 token 不再可见。页面提示"如遗失下载链接，请重新创建任务"

### 需求: 任务列表页线下任务展示

任务列表页（`/tasks`）必须为线下任务展示对应的状态标签和操作按钮。

#### 场景: 线下任务状态标签

- **当** 任务 `connectionType` 为 `"offline"`
- **那么** 表格行显示状态标签：
  - `CREATED` → "等待下载"（蓝色）
  - `SCRIPT_DOWNLOADED` → "等待执行"（蓝色）
  - `RESULTS_UPLOADED` → "已上传"（橙色）
  - `ANALYZING` → "分析中"（动画样式，与在线 RUNNING 相同）
  - `COMPLETED` → "已完成"（绿色）
  - `FAILED` → "失败"（红色）

#### 场景: 线下任务操作按钮

- **当** 任务 `connectionType` 为 `"offline"` 且状态为 `CREATED` 或 `SCRIPT_DOWNLOADED`
- **那么** 操作列显示"上传结果"按钮，点击后弹出文件选择对话框

#### 场景: 线下任务无重试按钮

- **当** 任务 `connectionType` 为 `"offline"`
- **那么** 操作列不显示"重试"按钮

#### 场景: 查看报告和日志

- **当** 线下任务状态为 `COMPLETED`
- **那么** 操作列显示"查看报告"和"查看日志"按钮，行为与在线任务一致

### 需求: 结果上传组件

系统必须提供结果上传功能，允许用户选择本地的 ZIP 文件并上传到平台。

#### 场景: 上传对话框

- **当** 用户点击某个线下任务的"上传结果"按钮
- **那么** 弹出上传对话框，包含文件选择输入（`accept=".zip"`）、上传进度条、确认按钮

#### 场景: 上传成功

- **当** 用户选择有效 ZIP 文件并点击确认，上传 API 返回 200
- **那么** 对话框显示"上传成功，正在进行分析"，任务列表自动刷新显示最新状态

#### 场景: 上传失败

- **当** 上传 API 返回错误（400/409/413/500）
- **那么** 对话框显示具体错误消息，允许用户重新选择文件

#### 场景: 上传中显示进度

- **当** 上传进行中
- **那么** 进度条显示实际上传进度（使用 `axios` 的 `onUploadProgress` 回调）

### 需求: 报告页兼容线下任务

报告查看页（`/report/:taskId`）必须兼容线下任务的数据展示。

#### 场景: 线下任务报告展示

- **当** 用户查看线下任务的报告
- **那么** 报告页面正常加载 `GET /api/tasks/{taskId}/report/json`，展示内容与在线报告一致（得分、通过率、Skill 卡片、命令明细、加固建议、时间线）

#### 场景: 线下回放标记

- **当** 渲染线下任务报告
- **那么** 页面顶部额外显示"执行模式：线下回放"标签，与在线模式的"执行模式：实时检测"区分

### 需求: 前端 API 客户端扩展

前端 `api/index.js` 必须新增与线下执行相关的 API 函数。

#### 场景: 脚本下载 API

- **当** 调用 `downloadScript(taskId, token)`
- **那么** 函数构造下载 URL `GET /api/tasks/{taskId}/script/download?token={token}`，`responseType: 'blob'`，返回 blob 供浏览器触发下载

#### 场景: 结果上传 API

- **当** 调用 `uploadResults(taskId, file)`
- **那么** 函数构造 `FormData`，追加 `file` 字段，`POST /api/tasks/{taskId}/results/upload`，Content-Type 自动设为 `multipart/form-data`
