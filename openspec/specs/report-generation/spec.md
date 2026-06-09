## 目的

报告生成负责汇总所有执行记录，由 AI 生成结构化安全报告（风险等级、关键证据、加固建议含 K8s YAML 补丁），并渲染为 HTML 页面，包含得分、通过率、环境信息、逐命令明细。

## 需求

### 需求: AI 生成结构化报告

当某个 Skill 的所有检测命令执行完毕后，系统必须调用阶段二 AI（报告生成器），收集全部 ExecutionRecord 作为上下文，生成结构化 JSON 报告。

#### 场景: FAIL 时生成报告

- **当** Skill 最终判定为 FAIL
- **那么** AI 返回的 JSON 必须包含 `testReport.riskLevel` 为 CRITICAL/HIGH/LOW 之一，`securityRemediation` 包含具体的加固策略和 K8s YAML 补丁

#### 场景: PASS 时生成报告

- **当** Skill 最终判定为 PASS
- **那么** AI 返回的 JSON 中 `testReport.riskLevel` 固定为 INFO，`securityRemediation.k8sYamlPatch` 为空字符串

#### 场景: 报告包含环境特定信息

- **当** 检测使用了上下文进化生成的新 Context
- **那么** AI 在报告 JSON 的 `securityRemediation.environmentSpecificNotes` 中提供针对当前环境的特别说明

### 需求: 报告 JSON 数据结构

阶段二 AI 返回的报告 JSON 必须包含以下必填字段，用于前端渲染。

#### 场景: 报告结构完整性校验

- **当** AI 返回报告 JSON
- **那么** 系统校验 `testReport.summary`（80 字以内）、`testReport.riskLevel`（枚举值）、`testReport.evidence`（非空）均存在；`securityRemediation.strategy` 存在且非空

#### 场景: 报告 JSON 解析失败

- **当** AI 返回的报告 JSON 无法解析
- **那么** 系统执行 JSON 清洗 → AI 自我修复流程，与阶段一解析逻辑一致

### 需求: 汇总报告数据

系统必须将所有 Skill 的检测结果汇总为一份完整的任务报告数据（ReportData），包含整体得分和通过率。

#### 场景: 计算整体得分

- **当** 4 个 Skill 中有 3 个 PASS、1 个 FAIL
- **那么** 系统计算 `overallScore = 75`，`passRate = "75%"`

#### 场景: 全通过

- **当** 所有 Skill 均为 PASS
- **那么** 系统计算 `overallScore = 100`，`passRate = "100%"`

### 需求: HTML 报告渲染

系统必须根据 ReportData 渲染为现代 HTML 页面，包含以下元素：

- **顶部区**：整体加固得分（环形图/进度条）、通过率、目标 IP、审计时间、目标环境信息标签（OS/发行版/内核/Shell/架构）
- **检测结果卡片**：每个 Skill 一张卡片。卡片显示 Skill 名称、PASS（绿色）/FAIL（红色）标签、使用的 Context 标签（含匹配类型）、进化标记、风险等级
- **命令执行明细**：每条命令可折叠展开，显示 stdout/stderr/exitCode/verdict
- **证据高亮**：FAIL 卡片中以红色边框高亮显示关键证据文本
- **加固建议**：修复策略描述、YAML 代码块语法高亮、替代建议、环境特定注意事项
- **时间线**：底部展示检测过程时间线（环境采集 → Context 选择 → 命令执行 → 进化记录 → 报告生成）

#### 场景: HTML 报告可访问

- **当** 请求 `GET /api/tasks/{taskId}/report`
- **那么** 系统返回完整的 HTML 页面（Content-Type: text/html），可直接在浏览器中渲染

### 需求: JSON 报告 API

系统必须提供 JSON 格式的报告数据端点，供程序化消费或后续扩展导出功能。

#### 场景: 获取 JSON 报告

- **当** 请求 `GET /api/tasks/{taskId}/report/json`
- **那么** 系统返回 Content-Type: application/json，包含完整的 ReportData 结构（taskId、targetIp、auditTime、overallScore、passRate、targetEnvironment、skillReports 数组）
