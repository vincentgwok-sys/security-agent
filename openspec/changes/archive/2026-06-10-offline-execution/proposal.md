## 为什么

当前平台要求目标容器必须通过网络可达（SSH 或 Kubectl jumpbox），这限制了在以下场景中的使用：
- 容器运行在隔离网络/气隙环境中，无法从外部建立 SSH 连接
- 安全策略禁止外部系统直接访问容器运行时
- 用户希望在将检测结果传回平台之前，先在本地审查原始数据

提供**线下执行模式**，让用户下载包含完整检测逻辑的 Python 脚本，在容器内离线执行后将结果上传回平台解析，使平台具备覆盖全场景的检测能力。

## 变更内容

- **新增**线下执行连接类型（`connectionType: "offline"`），创建任务时无需提供 SSH 凭据
- **新增** Python 脚本生成服务：根据选中的 Skill 和规则，生成可独立执行的 Python 检测脚本
- **新增**脚本下载端点：用户可下载生成的 Python 脚本
- **新增**结果上传与解析端点：接收线下执行产出的日志、报告、进化后的 Skill，解析并入库
- **新增**线下任务状态流转：CREATED → SCRIPT_DOWNLOADED → EXECUTING_OFFLINE → RESULTS_UPLOADED → ANALYZING → COMPLETED/FAILED
- **调整**前端任务创建页：新增「线下执行」模式选项，在选中后隐藏 SSH 配置、显示脚本下载区
- **调整**前端任务列表页：线下任务显示对应状态标签，上传入口替代在线任务的实时进度
- **调整**AI 分析流程：支持对上传的原始执行结果进行离线后分析（而非实时 SSH 流式分析）
- **调整**Skill 进化同步：上传的进化后 Skill 经过去重校验后合并到平台 Skill 库

## 功能 (Capabilities)

### 新增功能
- `offline-script-generation`: 根据选中的 Skill 和安全规则生成可独立执行的 Python 检测脚本，包含环境探针采集、命令执行、结果记录等完整流程
- `offline-result-upload`: 接收线下执行产出的 ZIP 包（含执行日志、原始命令输出、进化后的 Skill），解析验证后入库
- `offline-task-lifecycle`: 线下任务状态机管理，涵盖从脚本下载到结果上传完成的全生命周期

### 修改功能
- `security-detection`: 检测编排器需支持「线下回放模式」——对上传的原始命令输出执行 AI 判定，而非通过 SSH 实时执行
- `management-ui`: 任务创建页新增线下模式入口与脚本下载区；任务列表页新增线下任务状态标签与结果上传入口；报告查看页兼容线下任务数据
- `skill-evolution`: 上传的进化后 Skill 需与平台现有 Skill 合并去重，保留进化历史链
- `report-generation`: 线下任务的结果上传后触发异步 AI 报告生成，而非在检测过程中同步生成

## 影响

- **后端服务**：新增 ScriptGenerationService、OfflineResultService；修改 DetectionOrchestrator（支持线下回放）、TaskManagementService（新增状态）、SkillLoaderService（合并上传 Skill）
- **数据模型**：DetectionTask 新增 offlineDownloadToken、offlineUploadedAt 等字段；新增 OfflineExecutionResult 模型
- **API**：新增 `GET /api/tasks/{taskId}/script/download`、`POST /api/tasks/{taskId}/results/upload`；修改 `POST /api/tasks`
- **前端**：新增 CreateTask 页面的线下模式 UI、结果上传组件；修改 TaskList、ReportView
- **产物**：新增 `scripts/` 模板目录存放 Python 脚本模板
- **无破坏性变更**：现有 SSH/Kubectl 在线检测流程保持不变
