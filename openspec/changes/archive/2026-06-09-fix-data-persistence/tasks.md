## 1. 路径解析工具类

- [x] 1.1 创建 `PathResolver.java`：静态方法 `resolve(String configured)` — 判断路径是否绝对，是则原样返回，否则拼接 `System.getProperty("user.dir")` + `configured`

## 2. 任务持久化

- [x] 2.1 重写 `TaskManagementService`：新增 `@PostConstruct init()` 方法，启动时扫描 `reports/*.task.json` 反序列化回 `ConcurrentHashMap`
- [x] 2.2 `createTask()` 中，创建任务后调用 `persistTask(task)` 写入 `reports/{taskId}.task.json`
- [x] 2.3 `updateStatus()` 中，更新内存后立即调用 `persistTask(task)` 覆写文件
- [x] 2.4 `persistTask()` 私有方法：`objectMapper.writeValue()`，失败时记录 ERROR 日志不抛异常
- [x] 2.5 `reports/` 路径使用 `PathResolver.resolve()` 解析，确保绝对路径

## 3. AI 交互日志

- [x] 3.1 创建 `AiLogEntry.java` 数据模型：logId(UUID)、taskId、skillId、phase、systemPrompt、userPrompt、rawResponse、cleanedJson、parseResult、targetClass、costMs、timestamp
- [x] 3.2 创建 `AiLogService.java`：`log(AiLogEntry)` 方法，以 APPEND 模式写入 `logs/{taskId}/{skillId}.jsonl`，自动创建目录
- [x] 3.3 创建 `AiLogService.query(taskId, skillId, page, size)` 方法：扫描目录→读取 JSONL 行→按 timestamp 降序排序→分页返回
- [x] 3.4 修改 `AiClientService`：注入 `AiLogService`，在 `callAndParse()` 中记录 LLM 调用前后（记录 systemPrompt/userPrompt/rawResponse/cleanedJson/costMs/parseResult）
- [x] 3.5 `callAndParse()` 需要新增 `phase` 和 `skillId` 参数；各调用方法传递对应 phase（phase0/phase1/context-evolution/phase2）和 skillId
- [x] 3.6 `selfRepairJson()` 调用时单独记录一条 phase="json-repair" 的日志
- [x] 3.7 LLM 调用抛异常时也记录日志（rawResponse=异常消息，parseResult=FAILED）
- [x] 3.8 创建 `AiLogController.java`：`GET /api/logs/ai?taskId={taskId}&skillId={optional}&page=0&size=50`

## 4. Skills/Rules 路径修复

- [x] 4.1 修改 `SkillLoaderService`：`skillsDir` 字段在构造后通过 `PathResolver.resolve()` 包装
- [x] 4.2 修改 `CommandRuleService`：`rulesDir` 字段在构造后通过 `PathResolver.resolve()` 包装
- [x] 4.3 修改 `ReportGenerationService`：`reportsDir` 字段在构造后通过 `PathResolver.resolve()` 包装

## 5. 前端 AI 日志查看器

- [x] 5.1 创建 `AiLogViewer.vue`：页面路由 `/logs/:taskId`，按阶段标签彩色分组的折叠卡片列表
- [x] 5.2 每条日志卡片展示：阶段标签（phase0 蓝/phase1 绿/context-evolution 紫/phase2 橙/json-repair 黄）、时间、耗时、解析结果标色
- [x] 5.3 日志卡片展开后三选项卡：System Prompt（等宽字体）/ User Prompt（等宽字体）/ Response（原始响应 + 清洗后 JSON + 解析目标类型）
- [x] 5.4 `api/index.js` 添加 `fetchAiLogs(taskId, skillId, page, size)` 函数
- [x] 5.5 `router/index.js` 添加 `/logs/:taskId` 路由指向 `AiLogViewer`
- [x] 5.6 `TaskList.vue` 操作列增加"日志"按钮，点击跳转 `/logs/{taskId}`

## 6. 测试与验证

- [x] 6.1 单元测试 `PathResolver`：相对路径解析、绝对路径不变、null 安全
- [x] 6.2 单元测试 `TaskManagementService` 持久化：创建→文件存在、更新→文件更新、启动回读→任务恢复
- [x] 6.3 单元测试 `AiLogService`：写入 JSONL、按 taskId 查询、按 taskId+skillId 过滤、空目录查询返回空列表
- [x] 6.4 集成测试：完整流程（创建任务→AI 调用→日志生成→查询 API）验证全链路
- [x] 6.5 验证路径修复：以不同 `user.dir` 启动确认 skills/rules/reports 均可加载
