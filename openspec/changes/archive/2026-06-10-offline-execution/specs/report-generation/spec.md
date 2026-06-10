## 新增需求

### 需求: 线下任务异步报告生成

线下任务的报告生成必须在回放分析阶段异步触发，而非在检测过程中同步生成。

#### 场景: 回放完成后生成报告

- **当** 所有 Skill 的线下回放 AI 判定完成
- **那么** 系统调用 `ReportGenerationService.generateAndPersist()` 生成报告 JSON 和 HTML，与在线模式使用完全相同的方法

#### 场景: 报告可正常访问

- **当** 线下任务状态为 `COMPLETED`
- **那么** 请求 `GET /api/tasks/{taskId}/report` 返回 HTML 报告，`GET /api/tasks/{taskId}/report/json` 返回 JSON 报告，响应格式与在线任务完全一致

#### 场景: 部分 Skill 回放失败仍有报告

- **当** 回放分析中部分 Skill 的 AI 判定失败（如 JSON 解析失败超过重试次数）
- **那么** 系统仍生成报告，失败的 Skill 报告中标记 `finalStatus: "FAIL"`，`testReport.summary` 说明失败原因

### 需求: 报告数据包含执行模式标记

线下任务生成的报告数据必须标记执行模式，以区分实时检测和线下回放。

#### 场景: ReportData 包含 connectionType

- **当** 为线下任务生成 ReportData
- **那么** `ReportData` 中必须包含 `connectionType: "offline"` 字段，前端据此展示"执行模式：线下回放"标签

#### 场景: 在线任务 connectionType 不变

- **当** 为在线任务（SSH/Kubectl）生成 ReportData
- **那么** `ReportData` 中 `connectionType` 字段为 `"ssh"` 或 `"kubectl"`，前端展示相应标签
