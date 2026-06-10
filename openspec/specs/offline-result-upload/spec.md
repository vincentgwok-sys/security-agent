## 目的

线下结果上传允许用户将容器中离线执行的检测结果 ZIP 包上传回平台，系统对 ZIP 进行结构化校验，保存原始文件后触发异步 AI 回放分析。

## 需求

### 需求: 结果上传端点

系统必须提供 `POST /api/tasks/{taskId}/results/upload` 端点，接受线下执行产出的 ZIP 文件上传。

#### 场景: 成功上传 ZIP 文件

- **当** 用户通过 multipart/form-data 上传有效的 `task_{taskId}_result.zip`，请求 Content-Type 为 `multipart/form-data`，字段名为 `file`
- **那么** 系统保存 ZIP 到 `reports/{taskId}/offline_result.zip`，校验 ZIP 结构，将任务状态变更为 `RESULTS_UPLOADED`，返回 HTTP 200 包含 `taskId` 和消息"结果已上传，正在进行分析"

#### 场景: 任务不存在或非线下任务

- **当** `taskId` 对应的任务不存在，或任务 `connectionType` 不是 `"offline"`
- **那么** 系统返回 HTTP 404，错误消息说明任务不适用于线下结果上传

#### 场景: 任务状态不允许上传

- **当** 任务状态为 `COMPLETED`、`FAILED` 或 `ANALYZING`（已上传过）
- **那么** 系统返回 HTTP 409 Conflict，错误消息说明当前状态不允许上传

#### 场景: 上传文件不是有效 ZIP

- **当** 上传的文件无法被 `java.util.zip.ZipInputStream` 正常解析
- **那么** 系统返回 HTTP 400，错误消息"无效的 ZIP 文件格式"

#### 场景: 上传文件超大

- **当** 上传的 ZIP 文件超过 `spring.servlet.multipart.max-file-size` 配置（默认 50MB）
- **那么** 系统返回 HTTP 413 Payload Too Large

### 需求: ZIP 内容校验

系统必须在接受上传后对 ZIP 包内容进行结构化校验。

#### 场景: manifest.json 缺失

- **当** ZIP 包中不存在 `manifest.json`
- **那么** 系统返回 HTTP 400，错误消息"缺少 manifest.json"

#### 场景: manifest.json taskId 不匹配

- **当** `manifest.json` 中 `taskId` 字段与 URL 路径中的 `{taskId}` 不一致
- **那么** 系统返回 HTTP 400，错误消息"manifest 中的 taskId 与上传路径不匹配"

#### 场景: fingerprint.json 缺失

- **当** ZIP 包中不存在 `fingerprint.json`
- **那么** 系统返回 HTTP 400，错误消息"缺少 fingerprint.json"

#### 场景: execution_results 目录为空

- **当** `execution_results/` 目录下没有任何 `.json` 文件
- **那么** 系统返回 HTTP 400，错误消息"execution_results 目录中没有执行结果文件"

#### 场景: execution_results 中 Skill 数量不匹配

- **当** `manifest.json` 中 `skillIds` 列出 3 个 Skill，但 `execution_results/` 中仅有 2 个 `.json` 文件
- **那么** 系统返回 HTTP 400，错误消息指明缺失的 Skill ID

#### 场景: 校验全部通过

- **当** ZIP 包内所有必需文件存在、格式正确、taskId 匹配
- **那么** 系统进入下一步：触发异步 AI 回放分析

### 需求: 上传触发异步 AI 回放

ZIP 校验通过后，系统必须异步启动 AI 回放分析流程。

#### 场景: 异步启动回放

- **当** ZIP 校验通过
- **那么** 系统将任务状态变更为 `ANALYZING`，通过 `CompletableFuture.runAsync` 启动回放流程，上传 API 立即返回（不等待回放完成），返回消息"结果已上传，正在进行分析"

#### 场景: 回放完成

- **当** AI 回放分析成功完成，所有 Skill 的 AI 判定已执行，报告已生成
- **那么** 系统将任务状态变更为 `COMPLETED`，报告可正常通过 `GET /api/tasks/{taskId}/report` 和 `GET /api/tasks/{taskId}/report/json` 访问

#### 场景: 回放失败

- **当** AI 回放分析中发生不可恢复的错误（如 AI API 不可达、所有 Skill 解析失败）
- **那么** 系统将任务状态变更为 `FAILED`，`errorMessage` 记录失败原因，已完成的 Skill 分析结果保留

#### 场景: 回放中断

- **当** 服务在回放过程中被关闭
- **那么** 系统在启动恢复时将 `ANALYZING` 状态的任务标记为 `INTERRUPTED`（与 `RUNNING` 状态同等处理）

### 需求: 上传结果原始文件保留

系统必须保留用户上传的原始 ZIP 文件用于问题追溯。

#### 场景: 保留原始 ZIP

- **当** 上传成功
- **那么** 系统将原始 ZIP 保存到 `reports/{taskId}/offline_result.zip`，覆盖同名文件（如有）

#### 场景: 查询结果文件

- **当** 请求 `GET /api/tasks/{taskId}/results/download`
- **那么** 系统返回原始 ZIP 文件下载。如文件不存在则返回 404

### 需求: 上传大小限制可配置

系统必须支持通过 Spring Boot 配置调整上传大小限制。

#### 场景: 默认限制 50MB

- **当** `application.yml` 未显式配置 `spring.servlet.multipart.max-file-size`
- **那么** 系统默认限制为 50MB

#### 场景: 自定义限制

- **当** 配置 `spring.servlet.multipart.max-file-size: 100MB`
- **那么** 系统接受不超过 100MB 的 ZIP 上传
