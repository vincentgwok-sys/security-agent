## 目的

AI 交互日志记录每次 LLM 调用的完整上下文（Prompt、Response、清洗过程、解析结果），提供查询 API 和前端日志查看器，解决 AI 调用过程的黑盒问题。

## 需求

### 需求: AI 调用全链路记录

系统必须在每次调用大模型（阶段零、阶段一、上下文进化、阶段二、JSON 自我修复）时，创建一条 `AiLogEntry` 记录，包含完整输入输出信息。

日志条目必须包含以下字段：`logId`（UUID）、`taskId`、`skillId`、`phase`（phase0/phase1/context-evolution/phase2/json-repair）、`systemPrompt` 全文、`userPrompt` 全文、`rawResponse` 全文、`cleanedJson`（清洗后）、`parseResult`（SUCCESS/REPAIR_SUCCESS/FAILED）、`targetClass`（解析目标类型）、`costMs`（调用耗时毫秒）、`timestamp`（调用时间）。

#### 场景: 阶段一命令判定调用被记录

- **当** `AiClientService.judgeExecution()` 被调用
- **那么** 系统在 LLM 返回后创建一条 `AiLogEntry`，phase="phase1"，记录渲染后的 System/User Prompt、原始响应、清洗后 JSON、解析结果（SUCCESS/REPAIR_SUCCESS/FAILED），计算耗时并持久化到 `logs/{taskId}/` 目录

#### 场景: JSON 自我修复调用被记录

- **当** `AiClientService.selfRepairJson()` 被调用
- **那么** 系统创建一条 `AiLogEntry`，phase="json-repair"，记录损坏 JSON 原文和修复后输出

#### 场景: 阶段二报告生成调用被记录

- **当** `AiClientService.generateReport()` 被调用
- **那么** 系统创建一条 `AiLogEntry`，phase="phase2"，skillId 设为对应 Skill 的 skillId

#### 场景: 所有阶段调用均被记录

- **当** 任意 LLM 调用方法执行完毕（无论成功或失败）
- **那么** 系统确保一条 `AiLogEntry` 已写入日志文件——当 LLM 调用抛异常时，记录 rawResponse 为异常消息，parseResult 设为 FAILED

### 需求: AI 日志持久化为 JSON 行文件

每条 `AiLogEntry` 必须序列化为独立 JSON 行（NDJSON 格式），存储在 `logs/{taskId}/{skillId}.jsonl` 文件中，每行一条记录。

#### 场景: 日志追加写入

- **当** 一条新的 `AiLogEntry` 被创建
- **那么** 系统以追加模式（APPEND）将 JSON 一行写入对应 `logs/{taskId}/{skillId}.jsonl` 文件末尾

#### 场景: 日志目录自动创建

- **当** `logs/{taskId}/` 目录尚不存在
- **那么** 系统自动创建该目录后再写入日志文件

### 需求: AI 日志查询 API

系统必须提供 `GET /api/logs/ai` 端点，支持按 taskId 和分页参数查询 AI 交互日志。

#### 场景: 按 taskId 查询所有 Skill 的日志

- **当** 请求 `GET /api/logs/ai?taskId=TASK-20260609-0001&page=0&size=50`
- **那么** 系统扫描 `logs/TASK-20260609-0001/` 目录下所有 `.jsonl` 文件，读取全部行，按 timestamp 降序排列，分页返回

#### 场景: 按 taskId + skillId 过滤日志

- **当** 请求 `GET /api/logs/ai?taskId=TASK-001&skillId=SEC-K8S-CTX-001`
- **那么** 系统仅读取 `logs/TASK-001/SEC-K8S-CTX-001.jsonl`，按 timestamp 降序返回

#### 场景: taskId 对应日志不存在

- **当** 请求的 taskId 在 `logs/` 目录下无对应目录
- **那么** 系统返回空列表（HTTP 200），不返回 404

### 需求: 前端 AI 日志查看器

前端必须提供 AI 交互日志查看页面，路由为 `/logs/:taskId`，让用户查看每次 LLM 调用的完整上下文。

#### 场景: 日志卡片列表

- **当** 用户访问 `/logs/{taskId}`
- **那么** 前端调用 `GET /api/logs/ai?taskId={taskId}`，每条日志渲染为一张折叠卡片：头部显示阶段标签（带颜色区分）、调用时间、耗时（ms）、解析结果（绿色 SUCCESS / 黄色 REPAIR_SUCCESS / 红色 FAILED）

#### 场景: 日志卡片展开详情

- **当** 用户点击某条日志卡片展开
- **那么** 展示三个选项卡：**System Prompt**（等宽字体显示完整 system prompt）、**User Prompt**（等宽字体显示完整 user prompt）、**Response**（原始响应 + 清洗后 JSON 对比视图），以及解析目标类型和调用耗时

#### 场景: TaskList 入口

- **当** 用户在任务列表页（`/tasks`）查看某个任务
- **那么** 操作列除"查看报告"按钮外，还显示"查看日志"按钮，点击跳转到 `/logs/{taskId}`
