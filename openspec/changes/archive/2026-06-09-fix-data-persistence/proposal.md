## 为什么

当前 container-security-agent 存在三个阻塞性问题：1) 任务和报告数据存储在 JVM 内存 `ConcurrentHashMap` 中，服务重启后历史数据全部丢失；2) `skills/` 和 `rules/` 目录使用相对路径，重启后若工作目录变化则加载为空；3) 整个大模型交互过程（Prompt 发送、原始响应、JSON 清洗、自我修复）完全不可见，调试和审计无法进行。

## 变更内容

- **任务持久化**：将 `TaskManagementService` 中的内存 `ConcurrentHashMap` 替换为基于本地 JSON 文件的任务存储，每个任务序列化为 `reports/{taskId}.task.json`，启动时回读
- **Skill/Rules 路径修复**：将 `skills/` 和 `rules/` 的相对路径改为基于 `user.dir` 的绝对路径，启动时若目录不存在则自动创建并输出 WARN 日志（不静默创建空目录）
- **AI 交互日志**：新增 `AiLogEntry` 数据模型和 `AiLogService`，记录每次 LLM 调用的完整上下文（阶段标签、System/User Prompt 全文、原始响应、清洗后 JSON、解析结果、耗时），提供 `GET /api/logs/ai?taskId={taskId}&page=0&size=50` API 查询
- **前端日志查看器**：在 `TaskList.vue` 操作列增加"日志"按钮，新增 `AiLogViewer.vue` 页面展示每次 AI 调用的折叠卡片（阶段标签 + 耗时 + Prompt/Response 选项卡 + JSON 解析状态）

## 功能 (Capabilities)

### 新增功能
- `task-persistence`: 任务和报告数据从内存迁移到本地 JSON 文件持久化，服务重启后保留历史记录
- `ai-interaction-logging`: 记录每次 LLM 调用的完整上下文（Prompt、Response、解析过程），提供查询 API 和前端日志查看器

### 修改功能
- `skill-management`: Skills 目录路径从相对路径改为基于 `user.dir` 的绝对路径，确保任意启动位置均可正确加载
- `command-rules`: Rules 目录路径同样改为绝对路径（与 skill-management 一致的修复）

## 影响

- **代码**：`TaskManagementService` 重写存储层；新增 `AiLogService` + `AiLogController` + `AiLogEntry` 模型；修改 `SkillLoaderService` 和 `CommandRuleService` 的路径初始化逻辑
- **API**：新增 `GET /api/logs/ai` 查询端点；`GET /api/tasks` 现在返回持久化后的历史数据
- **前端**：新增 `AiLogViewer.vue` 页面 + 路由 `/logs/:taskId`；`TaskList.vue` 增加日志按钮
- **数据**：`reports/` 目录新增 `{taskId}.task.json` 任务存档文件；新增 `logs/` 目录存储 AI 交互日志（按 taskId 组织）
- **无破坏性变更**：现有 API 响应结构不变，仅补充数据来源
