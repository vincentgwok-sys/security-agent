## 新增需求

### 需求: 线下任务创建时生成 Python 脚本

系统必须在创建 `connectionType: "offline"` 的检测任务时，生成一个可独立执行的 Python 脚本。该脚本必须包含所选 Skill 的定义、命令安全规则、任务元数据，且仅依赖 Python 3.6+ 标准库。

#### 场景: 创建线下任务并生成脚本

- **当** 用户提交 `POST /api/tasks` 请求，`connectionType` 为 `"offline"`，包含 `skillIds` 列表
- **那么** 系统创建 `DetectionTask`（状态为 `CREATED`），生成唯一 `taskId` 和一次性下载令牌 `offlineDownloadToken`（UUID v4），将 Skill 定义 JSON 和规则 JSON 嵌入 Python 脚本模板，返回 202 Accepted 包含 `taskId` 和 `downloadToken`

#### 场景: 线下任务无需 SSH 凭据

- **当** 请求 `connectionType` 为 `"offline"`
- **那么** 系统不校验 `targetIp`、`sshUser`、`sshPassword` 字段，允许其为空

#### 场景: 脚本包含完整 Skill 定义

- **当** 用户选择 2 个 Skill（如 `SEC-K8S-CTX-001` 和 `SEC-K8S-CTX-002`）
- **那么** 生成的脚本必须内嵌这 2 个 Skill 的完整 `SkillDefinition` JSON（含所有 `executionContexts`、`envCheckCommands`、`detectionCommands`）

#### 场景: 脚本包含命令安全规则

- **当** 生成线下脚本时
- **那么** 脚本必须内嵌当前生效的所有命令安全规则 JSON（`CommandRule` 列表），在本地执行每条检测命令前进行规则匹配

#### 场景: 脚本仅依赖标准库

- **当** Python 脚本在目标容器中执行
- **那么** 脚本必须仅使用 Python 标准库模块（`subprocess`、`json`、`zipfile`、`os`、`sys`、`datetime`、`platform`、`argparse`、`base64`），禁止依赖 `pip install` 安装的第三方包

### 需求: 脚本下载端点

系统必须提供 `GET /api/tasks/{taskId}/script/download?token={token}` 端点，返回生成的 Python 脚本文件。

#### 场景: 成功下载脚本

- **当** 用户请求下载，提供有效的 `taskId` 和 `token`
- **那么** 系统返回 Content-Type: `text/x-python` 或 `application/octet-stream`，Content-Disposition: `attachment; filename="security_scan_{taskId}.py"`，HTTP 200

#### 场景: token 无效或已使用

- **当** 提供的 `token` 与 `taskId` 不匹配，或脚本已被下载过
- **那么** 系统返回 HTTP 404，错误消息说明 token 无效或已过期

#### 场景: 首次下载后 token 作废

- **当** 用户首次成功下载脚本
- **那么** 系统将任务状态变更为 `SCRIPT_DOWNLOADED`，记录下载时间，使原 token 不可再用于下载

#### 场景: 任务不存在

- **当** `taskId` 对应的任务不存在，或该任务 `connectionType` 不是 `"offline"`
- **那么** 系统返回 HTTP 404

### 需求: Python 脚本执行流程

脚本执行时必须按以下顺序进行：环境指纹采集 → Skill 遍历 → Context 匹配（程序侧，与 Java 逻辑一致）→ 命令执行并记录。

#### 场景: 采集环境指纹

- **当** 脚本启动
- **那么** 脚本合并所有 Skill 的 `envCheckCommands`（去重），逐条执行并收集输出，构造 `EnvironmentFingerprint` 对象（osType、osFlavors、kernelVersion、shellType、arch、availableTools），写入 `fingerprint.json`

#### 场景: 遍历 Skill 执行检测命令

- **当** 环境指纹采集完成
- **那么** 脚本对每个 Skill 执行 `selectBestContext` 逻辑（与 Java `SkillLoaderService.selectBestContext` 一致：优先精确匹配 → 模糊匹配 → 无匹配则跳过该 Skill，记录到日志）

#### 场景: 每条命令执行并记录结果

- **当** 选定 executionContext
- **那么** 脚本对每个 `detectionCommand`：先经规则引擎过滤（本地匹配），BLOCK 则跳过并记录到日志；通过则 `subprocess.run()` 执行（默认超时 30 秒），记录 command、stdout、stderr、exitCode、blocked、startedAt、endedAt 到 `execution_results/{skillId}.json`

#### 场景: 命令在环境探针中已失败

- **当** `envCheckCommands` 执行失败（如 `which capsh` 返回 exitCode > 0）
- **那么** 脚本仍必须继续执行检测命令——探针失败仅意味着该工具不可用，不影响检测逻辑

### 需求: 脚本结果打包

脚本执行完毕后，必须将全部结果打包为 ZIP 文件。

#### 场景: 打包为 ZIP

- **当** 所有 Skill 检测执行完毕（无论成功与否）
- **那么** 脚本生成 `task_{taskId}_result.zip`，包含 `manifest.json`、`fingerprint.json`、`execution_results/` 目录（每个 Skill 一个 JSON 文件）、`evolved_skills/` 目录（如有）、`execution.log`

#### 场景: manifest.json 内容完整

- **当** 生成打包结果
- **那么** `manifest.json` 必须包含：`taskId`（字符串）、`scriptVersion`（字符串，格式 `1.0.0`）、`executionStartedAt`（ISO 8601 时间戳）、`executionEndedAt`（ISO 8601 时间戳）、`hostname`（容器主机名）、`pythonVersion`（`sys.version` 输出）、`skillIds`（字符串数组）、`skillResults`（每个 Skill 的完成状态：`COMPLETED`/`SKIPPED`/`ERROR`）

#### 场景: 输出使用说明

- **当** 脚本执行完毕
- **那么** 脚本在 stdout 打印明确的下一步指示文本："检测完成。结果已打包到 {zip 文件名}。请将此 ZIP 文件上传到平台：{平台 URL}/tasks/{taskId}/upload"

### 需求: 脚本支持 Skill 筛选

脚本必须支持通过环境变量 `SKILL_IDS` 筛选执行的 Skill，允许用户仅执行部分 Skill。

#### 场景: 通过环境变量筛选

- **当** 执行 `SKILL_IDS=SEC-K8S-CTX-001,SEC-K8S-CTX-003 python security_scan_TASK-xxx.py`
- **那么** 脚本仅执行 `SEC-K8S-CTX-001` 和 `SEC-K8S-CTX-003` 的检测，跳过其余 Skill

#### 场景: 未设置环境变量时执行全部

- **当** 未设置 `SKILL_IDS` 环境变量
- **那么** 脚本执行脚本内嵌的全部 Skill

### 需求: Python 版本兼容性检查

脚本必须在启动时检查 Python 版本，低于最低要求时明确退出。

#### 场景: Python 版本过低

- **当** 执行环境 Python 版本低于 3.6
- **那么** 脚本打印错误信息："错误：此脚本需要 Python 3.6 或更高版本。当前版本：{version}"，并以 exit code 1 退出

#### 场景: Python 版本满足要求

- **当** 执行环境 Python 版本 ≥ 3.6
- **那么** 脚本正常继续执行
