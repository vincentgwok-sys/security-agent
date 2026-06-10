## 上下文

当前平台（container-security-agent）是一个 Spring Boot 3.4 + Vue 3 的容器安全检测系统。核心流程为：用户通过 Web UI 创建检测任务 → TaskManagementService 持久化任务 → DetectionOrchestrator 异步执行「环境指纹采集 → Context 匹配 → 逐命令 SSH 执行 + AI 判定 → 报告生成」→ 结果持久化到 `reports/`。

**约束**：
- 检测逻辑与 SSH 传输层紧密耦合：`DetectionOrchestrator.executeSkillDetection()` 中 SSH 执行和 AI 判定在同一循环内交替进行
- Skill 进化（命令级/上下文级）依赖于实时 AI 反馈
- 所有 AI 调用日志按 `logs/{taskId}/{skillId}.jsonl` 持久化
- Task 状态机当前仅支持：CREATED → RUNNING → COMPLETED/FAILED/INTERRUPTED
- 前端通过 REST API 通信，无需鉴权
- 项目不使用数据库，所有数据以 JSON 文件持久化

**利益相关者**：安全运维人员（在隔离网络中运行检测）、平台管理员（管理 Skill 库和检测结果）

## 目标 / 非目标

**目标：**
1. 用户可创建「线下执行」任务，无需提供 SSH 凭据
2. 平台生成包含完整检测逻辑、规则和 Skill 定义的独立 Python 脚本
3. 用户在容器中执行脚本后，将结果 ZIP 包上传回平台
4. 平台对上传结果执行 AI 回放分析（与在线检测逻辑同构），生成报告
5. 上传的进化后 Skill 经去重校验后合并到平台 Skill 库
6. 线下任务有独立的状态机流转，前端各页面兼容线下任务

**非目标：**
- 不支持线下脚本的远程执行调度（那是 CI/CD 系统的职责）
- 不提供 Python 脚本以外的运行时（不生成 Shell/Go/Bash 版本）
- 不修改现有 SSH/Kubectl 在线检测流程
- 不支持部分结果上传——每次上传必须是完整的 ZIP 包
- 不支持断点续传或增量上传

## 决策

### 1. Python 脚本架构：嵌入式数据 + 标准库

**选择**：生成单个 `.py` 文件，Skill 定义和规则 JSON 作为 Base64 嵌入脚本内部，运行时解码使用。脚本仅依赖 Python 3.6+ 标准库。

**理由**：
- 容器环境通常仅预装 Python（无 pip/外部网络），单文件传输最可靠
- Python 跨平台（Linux 容器标准），`subprocess` + `json` + `zipfile` 均为标准库
- Base64 内嵌避免了脚本运行时需要引用外部 JSON 文件的路径问题

**替代方案**：
- ❌ 生成 ZIP（脚本 + JSON 配置文件）——需要解压，增加步骤，且某些容器无 unzip
- ❌ 网络调用 API（脚本实时回传结果）——违背「线下」场景
- ❌ Shell 脚本 —— 复杂的 JSON 构造和数据组装在 Shell 中难以维护

### 2. 脚本模板引擎：Mustache

**选择**：复用项目已有的 Mustache 模板引擎（`PromptTemplateEngine`），在 `scripts/templates/` 下存放 Python 脚本模板，由后端填充后返回。

**理由**：
- 减少新依赖。项目已使用 Mustache 渲染 AI Prompt，复用同一套 `PromptTemplateEngine`
- 模板可维护性强：Python 代码逻辑修改时只需改 `.py.mustache` 文件，无需重新编译
- 模板中可嵌入 `{{skillJson}}`、`{{rulesJson}}` 等变量，由后端在生成时注入

**替代方案**：
- ❌ Java 字符串拼接 —— 可读性差、难以维护
- ❌ Jinja2 服务端渲染 —— 引入 Python 依赖到 Java 项目，运维复杂

### 3. 结果包格式：ZIP 文件，结构化 JSON

**选择**：ZIP 包内包含以下固定结构：

```
task_{taskId}_result.zip
├── manifest.json          # 任务元数据（taskId, scriptVersion, executionStartedAt, executionEndedAt, hostname, pythonVersion）
├── fingerprint.json       # EnvironmentFingerprint 序列化结果
├── execution_results/
│   ├── {skillId}.json     # 每个 Skill 的执行记录数组（ExecutionRecord 模型子集：command, stdout, stderr, exitCode, blocked, startedAt, endedAt）
├── evolved_skills/
│   ├── {skillId}-{ts}.json # 进化后的 Skill 定义（如有）
├── execution.log          # 人类可读的文本日志
```

**理由**：
- 与 Java 端 `ExecutionRecord` / `SkillDefinition` 模型保持字段对齐，反序列化零转换
- `manifest.json` 提供完整性校验（记录预期 Skill 数量、脚本版本）
- `execution_results/` 按 Skill 拆分，避免单文件过大
- 支持未来扩展（如增加 `raw_outputs/` 目录）
- MIME 类型：`multipart/form-data`

**替代方案**：
- ❌ tar.gz —— Python `tarfile` 包含，但 Java 端处理不如 ZIP（`java.util.zip` 内置）
- ❌ 单个 JSON 文件 —— 大任务可能超过内存限制
- ❌ 邮件/外部存储上传 —— 用户操作链路过长

### 4. 线下回放模式：DetectionOrchestrator 新增分支

**选择**：在 `DetectionOrchestrator` 中新增 `executeOfflineReplay(task, preRecordedResults, uploadedSkills)` 方法。该方法跳过 SSH 执行阶段，直接从 `ExecutionRecord` 中读取 `stdout/stderr/exitCode`，送交 `AiClientService.judgeExecution()` 进行 AI 判定。判定逻辑与在线模式完全相同。

关键差异：

| 阶段 | 在线 | 线下回放 |
|------|------|----------|
| 环境指纹 | SSH 探针采集 | 读取 `fingerprint.json` |
| Context 选择 | 同上 | 同上（复用 `SkillLoaderService.selectBestContext`，但 Skill 来自上传合并后的版本） |
| 命令执行 | SSH 实时执行 | 从 `execution_results/` 按序读取 |
| AI 判定 | 实时调用 | 回放调用（同样的 Prompt，同样的逻辑） |
| 命令进化 | AI 返回 EVOLVE → SSH 执行新命令 | 不支持——上传结果中无新命令输出 |
| 上下文进化 | AI 返回 ENV_MISMATCH → 触发进化 | 不支持——上传结果中无新环境探针 |
| 报告生成 | 同步调用 AI | 同步调用 AI |

**理由**：
- 最大化代码复用：`AiClientService`、`SkillLoaderService.selectBestContext`、`ReportGenerationService` 全部复用
- 回放模式明确禁止命令/上下文进化（因为容器不可达），简化了异常处理
- 上传结果中包含的 `evolved_skills/` 在回放开始前合并，回放时使用合并后的 Skill 进行判定

**替代方案**：
- ❌ 新建独立 `OfflineReplayService` —— 大量代码与 `DetectionOrchestrator` 重复
- ❌ 离线脚本也调用 AI —— 脚本运行环境无 AI API 可达

### 5. Skill 合并策略：去重后追加

**选择**：上传的 `evolved_skills/` 中的 Skill 文件与平台现有 Skill 合并时：
1. 按 `skillId` 分组
2. 对上传 Skill 的每个 `executionContext`，检查是否与平台已有 context 重复（`osType + osFlavor` 完全匹配且 `requiredTools` 完全子集）
3. 无重复则追加到 Skill 的 `executionContexts` 数组
4. 新 context 标记 `evolvedFrom="offline-execution"`、`evolvedAt=当前时间戳`
5. 按现有规范持久化为 `{skillId}-{newTimestamp}.json`

**理由**：
- 复用现有 `SkillLoaderService.saveEvolvedSkill()` 机制
- 线下执行环境可能与在线环境不同，需要保留其进化成果
- `evolvedFrom="offline-execution"` 标记便于追溯

**替代方案**：
- ❌ 直接覆盖平台 Skill —— 可能丢失在线模式产生的有效 Context
- ❌ 上传 Skill 作为独立副本 —— 造成 Skill 库膨胀，相同 skillId 出现两条演进树

### 6. 任务状态机扩展

**选择**：新增 4 个状态，扩展为 9 状态。`TaskStatus` 枚举新增：

```
CREATED → SCRIPT_DOWNLOADED → EXECUTING_OFFLINE → RESULTS_UPLOADED → ANALYZING → COMPLETED
                                                                         ↘ FAILED
                                                                         ↘ INTERRUPTED
```

- 在线任务路径不变：CREATED → RUNNING → COMPLETED/FAILED/INTERRUPTED
- 线下任务路径：
  - `CREATED`：任务已创建，脚本可供下载
  - `SCRIPT_DOWNLOADED`：用户已下载脚本（通过 API 触发状态变更）
  - `EXECUTING_OFFLINE`：标记为离线执行中（平台侧无感知，仅表示脚本已下載）
  - `RESULTS_UPLOADED`：用户已上传结果 ZIP
  - `ANALYZING`：平台正在对上传结果做 AI 回放分析
  - `COMPLETED` / `FAILED`：分析完成/失败

**理由**：
- 新增状态不改变现有在线任务的状态流转
- `SCRIPT_DOWNLOADED` 标记可防止脚本被重复下载（通过 token 失效机制）
- `EXECUTING_OFFLINE` 提供前端清晰的「等待上传」状态提示

**替代方案**：
- ❌ 不新增状态，复用现有 5 状态 —— 前端无法区分「等待 SSH 执行」和「等待上传结果」，用户体验差
- ❌ 新增独立 `OfflineTaskStatus` 字段 —— 增加模型复杂度，前端需要两套展示逻辑

### 7. 脚本下载安全：一次性 Token

**选择**：创建线下任务时生成 `offlineDownloadToken`（UUID）。下载端点 `GET /api/tasks/{taskId}/script/download?token={token}` 校验 token，首次下载后将任务状态变更为 `SCRIPT_DOWNLOADED` 并作废 token。

**理由**：
- 无鉴权系统下防止未授权下载（token 随机不可猜测）
- 一次性使用避免脚本被分发到多台机器导致结果混乱
- UUID v4 提供足够的熵（碰撞概率极低）

### 8. 上传大小限制与校验

**选择**：Spring Boot 配置 `spring.servlet.multipart.max-file-size: 50MB`，上传后校验：
1. ZIP 可正常解压
2. `manifest.json` 存在且格式正确，`taskId` 与 URL 路径匹配
3. `execution_results/` 下文件数量与 `manifest.json` 中 `skillIds` 数量一致
4. 每个 `execution_results/{skillId}.json` 是合法的 JSON，记录可反序列化
5. `fingerprint.json` 包含必填字段（osType、osFlavors）

**理由**：多层校验防止格式错误导致 AI 回放失败，提前报错给用户。

## 风险 / 权衡

1. **[风险] Python 脚本兼容性**：目标容器 Python 版本可能为 3.5 或更低（f-string 不可用）。
   → **缓解**：脚本以 Python 3.6 为目标（使用 `.format()` 而非 f-string，使用 `subprocess.run(capture_output=True)` 时 fallback 到 `Popen`）。脚本开头做版本检查，低于 3.6 时退出并打印明确错误信息。

2. **[风险] 大文件上传**：容器输出可能非常大（如 `cat /proc/*` 的完整输出），ZIP 可能超过内存限制。
   → **缓解**：脚本中对 stdout/stderr 做截断（每条命令输出限制 500KB），由脚本内常量控制；Spring Boot 端使用流式 ZIP 解析（`ZipInputStream`）而非全量加载。

3. **[风险] 回放结果与在线检测不一致**：线上脚本执行和上传回放之间存在时间差，AI 模型对同一输入可能给出不同判定。
   → **缓解**：这是可接受的——AI 判定本质上是概率性的，回放结果即为最终结果。`manifest.json` 记录原始执行时间戳，前端标注"离线回放"以示区分。

4. **[风险] 进化后的 Skill 质量**：线下脚本中的进化逻辑（如果未来加入）可能不如在线模式精细。
   → **缓解**：V1 线下脚本不包含 AI 进化逻辑——它仅执行固定命令并记录结果。进化仅在上传后的回放阶段发生（如果 AI 判定为 EVOLVE，由于目标不可达，仅记录而不执行替代命令）。

5. **[权衡] 线下任务不支持重试**：原任务已消耗 SSH 连接/时间，重试无意义（线下任务不涉及 SSH）。
   → **缓解**：`connectionType: "offline"` 的任务在前端隐藏"重试"按钮。用户如需重试可重新创建任务并下载新脚本。

## 待定问题

1. Python 脚本中是否需要嵌入命令安全规则？—— 建议 V1 嵌入（脚本本地过滤危险命令），避免线下执行误操作。规则 JSON 同样 Base64 嵌入。
2. 是否支持单个 Skill 的独立脚本？—— 建议 V1 支持：创建任务时选中多个 Skill，但脚本生成支持按 Skill 独立执行。用户可通过环境变量 `SKILL_IDS=SEC-K8S-CTX-001,SEC-K8S-CTX-002` 筛选。
3. 上传后的结果 ZIP 是否保留原始文件？—— 建议保留在 `reports/{taskId}/offline_result.zip` 以便问题追溯。
