## 上下文

当前 container-security-agent 所有运行时数据（任务列表、AI 返回结果）存在 JVM 内存 `ConcurrentHashMap` 中，服务重启即丢失。`skills/`、`rules/`、`reports/` 三个数据目录使用 Spring `@Value("${...:./default}")` 注入的相对路径，但 `Paths.get(relativePath)` 依赖 JVM `user.dir`，这在 IDE 启动和 `java -jar` 启动时通常不同，导致 Skill 和规则加载为空。

此外，`AiClientService` 中的 JSON 清洗和自我修复逻辑完全黑盒——Prompt 内容、原始响应、解析过程无任何持久化记录，出现异常后只能靠日志片段猜测。

**约束**：
- 不使用外部数据库（MySQL、Redis 等），保持 light-weight
- 文件持久化方案必须兼容 Windows 和 Linux 路径
- 不引入新的 Maven 依赖

## 目标 / 非目标

**目标：**
- 任务数据持久化到本地 JSON 文件，重启后可恢复
- Skills / Rules / Reports / Logs 四个目录路径统一为基于 `user.dir` 的绝对路径
- 每次 LLM 调用记录完整上下文（Prompt + Response + 解析结果）到 NDJSON 日志文件
- 提供 AI 日志查询 API 和前端查看器

**非目标：**
- 不引入 SQL 数据库
- 不实现日志轮转/归档策略（本版仅按 taskId 组织，后续可扩展）
- 不实现 WebSocket 实时推送（前端通过轮询刷新）
- 不改变现有 API 响应结构

## 决策

### D1: 任务持久化使用单文件 JSON（`reports/{taskId}.task.json`）

**决策**：每个任务序列化为一个独立 JSON 文件，命名格式 `{taskId}.task.json`，存储在 `reports/` 目录下。与现有的 `{taskId}.json` 报告文件共处同一目录，清晰区分文件用途。

**理由**：单文件策略比聚合文件（如 `tasks.json`）更适合——无需担心并发写入冲突（每个任务独立写各自文件），且删除单个任务时无需全量重写。JSON 格式与 Jackson 序列化天然兼容，无需额外依赖。

**替代方案**：
- 方案 A（已否决）：SQLite 嵌入数据库 → 引入新依赖，增加复杂度
- 方案 B（已否决）：单个 `tasks.json` 聚合文件 → 并发更新时需文件锁，写入放大

**启动恢复流程**：
```
TaskManagementService @PostConstruct
  → Files.list(reportsDir, "*.task.json")
  → 逐行 objectMapper.readValue(file, DetectionTask.class)
  → ConcurrentHashMap.putAll(loadedTasks)
  → 记录 INFO: "从磁盘恢复 X 个历史任务"
```

### D2: 路径统一通过 `PathResolver` 工具类解析

**决策**：创建 `PathResolver.java` 工具类，在 `SkillLoaderService`、`CommandRuleService`、`ReportGenerationService`、`AiLogService` 中统一调用。

```java
public class PathResolver {
    public static Path resolve(String configured) {
        Path p = Path.of(configured);
        return p.isAbsolute() ? p : Path.of(System.getProperty("user.dir")).resolve(p);
    }
}
```

**理由**：四个服务各自处理路径逻辑会导致不一致。集中在一个工具类中，配置相对路径时统一拼接 `user.dir`，已为绝对路径时保持不变。不侵入 Spring `@Value` 注入方式，仅在服务中使用值前包装。

**替代方案**：
- 方案 A（已否决）：修改 `application.yml` 默认值 → 不在 YAML 层面解决，`${user.dir}` 占位符不支持
- 方案 B（已否决）：`@PostConstruct` 中修改字段值 → 对 `@Value` 注入的字段在构造后修改不够透明

### D3: AI 日志使用 NDJSON 格式（每行一条 JSON）按 taskId + skillId 组织

**决策**：日志文件结构为 `logs/{taskId}/{skillId}.jsonl`，每行一个 JSON 对象（NDJSON 格式）。`AiLogService` 以追加模式（`StandardOpenOption.APPEND`）写入。

**理由**：NDJSON 天然适合追加写入——无需维护 JSON 数组括号，每条记录独立成行便于 grep/head/tail 分析。按 taskId 分目录、按 skillId 区分文件，避免单文件过大。

**文件结构**：
```
logs/
  TASK-20260609-0001/
    SEC-K8S-CTX-001.jsonl
    SEC-K8S-CTX-002.jsonl
  TASK-20260609-0002/
    SEC-K8S-CTX-001.jsonl
```

**AiLogEntry 结构**：
```java
record AiLogEntry(
    String logId,        // UUID
    String taskId,
    String skillId,
    String phase,        // phase0|phase1|context-evolution|phase2|json-repair
    String systemPrompt, // 全文
    String userPrompt,   // 全文
    String rawResponse,  // 全文
    String cleanedJson,  // 清洗后
    String parseResult,  // SUCCESS|REPAIR_SUCCESS|FAILED
    String targetClass,  // AiVerdict|ContextMatchResult|etc.
    long costMs,         // 调用耗时
    long timestamp       // Unix 毫秒
)
```

**替代方案**：
- 方案 A（已否决）：存储在 Reports JSON 中 → 报告会变得臃肿，且日志应在报告生成前即可查看
- 方案 B（已否决）：SLF4J 文件 Appender → 结构化查询困难，无法按 taskId/skillId 过滤

### D4: AiClientService 内部使用 AOP 或直接调用 `AiLogService.log()`

**决策**：不引入 AOP，在 `AiClientService.callAndParse()` 方法中直接注入 `AiLogService`，在 LLM 调用前后记录。每个 LLM 调用方法（`matchEnvironment`、`judgeExecution`、`evolveNewContext`、`generateReport`）传递 phase 参数到 `callAndParse`，由 `callAndParse` 统一记录。

**理由**：AOP 需要 Spring AOP 或 AspectJ 依赖，且对 private 方法无效。直接注入 `AiLogService` 更简单透明——调用方知道自己在记录日志，日志的 phase/skillId 由调用方明确传递。

**调用链路变更**：
```
AiClientService.judgeExecution(skill, ctx, env, cmd, idx, total, result)
  → callAndParse(system, user, AiVerdict.class)
    → long start = System.currentTimeMillis()
    → String raw = chatClient.prompt()...call().content()
    → String cleaned = JsonCleaner.clean(raw)
    → parse → success / selfRepair / fail
    → long cost = System.currentTimeMillis() - start
    → aiLogService.log(new AiLogEntry(..."phase1", skillId, ...))
    → return parsed
```

### D5: 前端日志查看器新增路由 `/logs/:taskId`，通过现有 API 层加载

**决策**：新增 `AiLogViewer.vue` 组件，路由 `/logs/:taskId`。在 `router/index.js` 添加路由，在 `api/index.js` 添加 `fetchAiLogs(taskId, page, size)` 函数。TaskList 操作列增加"日志"链接。

**理由**：与现有页面模式一致——每个页面独立路由、独立 API 调用。不引入全局状态管理（Vuex/Pinia），直接用组件内 `ref` + `onMounted` 加载。

## 风险 / 权衡

| 风险 | 缓解措施 |
|------|----------|
| 高并发写入 `task.json` 时文件系统瓶颈 | 当前 Task 创建为单线程（`CompletableFuture.runAsync` 使用默认 ForkJoinPool），每个 taskId 的文件独立，无竞争 |
| NDJSON 日志文件随时间增长 | 每条日志约 5-15KB（含完整 Prompt），按 ~100 次 LLM 调用计约 1.5MB/task。后续版本可增加 30 天自动清理策略 |
| `user.dir` 在某些容器环境未知 | `PathResolver` 在解析失败时回退到原始配置路径并记录 WARN 日志 |
| 启动时大量历史文件拖慢启动 | 当前量级（几十到几百个 task）影响可忽略。后续可增加懒加载或分页加载 |
| 前端日志页面加载大量 JSON 数据 | API 分页（默认 50 条/页），前端懒加载 |
