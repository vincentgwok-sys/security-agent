## 1. 数据模型变更

- [x] 1.1 扩展 `TaskStatus` 枚举：新增 `SCRIPT_DOWNLOADED`、`RESULTS_UPLOADED`、`ANALYZING` 三个状态值
- [x] 1.2 扩展 `DetectionTask` 实体：新增 `offlineDownloadToken`（String）、`scriptDownloadedAt`（LocalDateTime）、`resultUploadedAt`（LocalDateTime）字段
- [x] 1.3 新增 `OfflineManifest` 模型：对应结果包中 `manifest.json` 结构（taskId、scriptVersion、executionStartedAt、executionEndedAt、hostname、pythonVersion、skillIds、skillResults）
- [x] 1.4 扩展 `ReportData` 实体：新增 `connectionType` 字段，用于前端区分执行模式
- [x] 1.5 扩展 `TaskController.CreateTaskRequest` record：`connectionType` 为 `"offline"` 时 `targetIp`/`sshUser`/`sshPassword` 不再标记为 `@NotBlank`（使用自定义校验逻辑）

## 2. Python 脚本模板

- [x] 2.1 创建 `scripts/templates/` 目录，添加 `security-scan.py.mustache` 模板文件
- [x] 2.2 实现环境指纹采集逻辑：合并所有 Skill 的 `envCheckCommands` 去重后逐条执行，解析输出构造 `EnvironmentFingerprint` JSON
- [x] 2.3 实现 Context 匹配逻辑：与 Java `SkillLoaderService.selectBestContext` 同构（优先精确匹配 → 模糊匹配）
- [x] 2.4 实现命令执行与结果记录：逐 Skill、逐命令执行（`subprocess.run`，timeout=30s），记录 command/stdout/stderr/exitCode/blocked/startedAt/endedAt 到 `execution_results/{skillId}.json`
- [x] 2.5 实现本地命令规则引擎：内嵌规则 JSON，支持 EXACT/PREFIX/REGEX 三种匹配类型，BLOCK 则跳过命令
- [x] 2.6 实现结果打包：将 manifest.json、fingerprint.json、execution_results/、evolved_skills/、execution.log 打包为 ZIP
- [x] 2.7 添加 Python 版本检查（≥ 3.6）、SKILL_IDS 环境变量筛选、stdout 使用说明输出

## 3. 后端：脚本生成服务

- [x] 3.1 新增 `ScriptGenerationService`：读取 Mustache 模板，注入 Skill JSON、规则 JSON、taskId 等变量，生成完整 Python 脚本字符串
- [x] 3.2 在 `TaskController.createTask` 中集成：当 `connectionType` 为 `"offline"` 时，调用 `ScriptGenerationService` 生成脚本，生成 `offlineDownloadToken`（UUID），将 token 和脚本内容缓存在内存中（或写入临时文件）
- [x] 3.3 新增 `GET /api/tasks/{taskId}/script/download?token={token}` 端点：校验 token，返回 Python 脚本文件（Content-Disposition: attachment），首次下载后将任务状态变更为 `SCRIPT_DOWNLOADED` 并作废 token

## 4. 后端：任务管理适配

- [x] 4.1 修改 `TaskManagementService.createTask`：支持 `connectionType: "offline"`，跳过 SSH 连接校验，生成 `offlineDownloadToken`
- [x] 4.2 修改 `TaskManagementService.init`（启动恢复）：正确处理 `SCRIPT_DOWNLOADED`、`RESULTS_UPLOADED`、`ANALYZING` 三种新状态的恢复——`ANALYZING` 标记为 `INTERRUPTED`
- [x] 4.3 修改重试逻辑：`connectionType` 为 `"offline"` 的任务拒绝重试请求，返回 HTTP 400

## 5. 后端：结果上传与校验

- [x] 5.1 新增 `OfflineResultService`：包含 `validateAndExtract(ZipInputStream)` 方法，校验 ZIP 结构完整性
- [x] 5.2 实现 ZIP 校验逻辑：检查 `manifest.json` 存在且 taskId 匹配、`fingerprint.json` 存在、`execution_results/` 非空、文件数量与 manifest 中 skillIds 一致
- [x] 5.3 实现 `parseExecutionRecords`：从 `execution_results/{skillId}.json` 反序列化 `ExecutionRecord` 列表，构造 `ExecutionResult` 对象
- [x] 5.4 实现 `parseFingerprint`：从 `fingerprint.json` 反序列化 `EnvironmentFingerprint`
- [x] 5.5 实现 `extractEvolvedSkills`：从 `evolved_skills/` 目录读取 Skill 文件，反序列化为 `SkillDefinition` 列表
- [x] 5.6 新增 `POST /api/tasks/{taskId}/results/upload` 端点：接收 multipart 上传，校验 → 保存原始 ZIP → 触发异步回放 → 返回 200
- [x] 5.7 新增 `GET /api/tasks/{taskId}/results/download` 端点：返回原始上传 ZIP 文件下载

## 6. 后端：线下回放分析

- [x] 6.1 在 `DetectionOrchestrator` 中新增 `executeOfflineReplay(DetectionTask, List<SkillDefinition>, EnvironmentFingerprint, Map<String, List<ExecutionRecord>>)` 方法
- [x] 6.2 实现回放模式的 Context 匹配：使用合并后的 Skill 调用 `SkillLoaderService.selectBestContext`
- [x] 6.3 实现回放模式的命令判定循环：从预录 `ExecutionRecord` 中读取 stdout/stderr/exitCode，构造 `ExecutionResult`，调用 `AiClientService.judgeExecution`（与在线模式同 Prompt），处理 PASS/FAIL/EVOLVE/ENV_MISMATCH——EVOLVE 和 ENV_MISMATCH 仅记录不执行替代命令/上下文进化
- [x] 6.4 实现回放模式的报告生成：所有 Skill 判定完成后调用 `AiClientService.generateReport`
- [x] 6.5 在 `OfflineResultService` 中编排回放流程：合并上传 Skill → 启动回放 → 持久化报告

## 7. 后端：Skill 合并

- [x] 7.1 在 `SkillLoaderService` 中新增 `mergeUploadedSkill(SkillDefinition uploadedSkill)` 方法：按 skillId 查找平台 Skill，逐个 executionContext 去重比较（osType + osFlavor 完全匹配 + requiredTools 子集关系），无重复则追加并标记 `evolvedFrom="offline-execution"`
- [x] 7.2 实现全新 Skill 的导入：上传的 skillId 在平台不存在时，直接持久化为新文件
- [x] 7.3 实现合并后的持久化：有新增 Context 时调用 `saveEvolvedSkill` 生成新版本文件

## 8. 后端：报告生成适配

- [x] 8.1 修改 `ReportData` 构建逻辑：`buildReportData` 方法增加 `connectionType` 参数，写入到报告数据中
- [x] 8.2 修改 HTML 报告渲染：在线检测报告页面顶部增加执行模式标签（"实时检测" / "线下回放"）

## 9. 前端：API 客户端扩展

- [x] 9.1 在 `api/index.js` 中新增 `downloadScript(taskId, token)` 函数：GET 请求，`responseType: 'blob'`，返回 blob
- [x] 9.2 在 `api/index.js` 中新增 `uploadResults(taskId, file)` 函数：POST multipart/form-data 上传
- [x] 9.3 在 `api/index.js` 中新增 `downloadResultZip(taskId)` 函数：GET 请求下载原始 ZIP

## 10. 前端：任务创建页改造

- [x] 10.1 在 `CreateTask.vue` 连接方式选择区新增「📥 线下执行」选项（`connectionType: 'offline'`）
- [x] 10.2 实现选择「线下执行」时隐藏 SSH/Kubectl 配置区域，仅显示 Skill 选择区域
- [x] 10.3 实现线下任务创建成功后的脚本下载区域：显示下载按钮和 token 提示（仅首次可见），token 存储在组件状态中（页面刷新后丢失）
- [x] 10.4 实现下载按钮点击逻辑：调用 `downloadScript` API，将 blob 保存为文件（使用 `URL.createObjectURL` + `<a>` 点击触发下载）

## 11. 前端：任务列表页改造

- [x] 11.1 在 `TaskList.vue` 中新增连接方式图标列：SSH（🔗）、Kubectl（⎈）、Offline（📥）
- [x] 11.2 新增线下任务状态标签映射：`CREATED`→"等待下载"、`SCRIPT_DOWNLOADED`→"等待执行"、`RESULTS_UPLOADED`→"已上传"、`ANALYZING`→"分析中"（动画）
- [x] 11.3 实现线下任务操作按钮：`CREATED`/`SCRIPT_DOWNLOADED` 状态显示"上传结果"按钮，隐藏"重试"按钮；`COMPLETED` 显示"查看报告"和"查看日志"

## 12. 前端：结果上传组件

- [x] 12.1 新增 `UploadDialog.vue` 组件：文件选择输入（accept=".zip"）、上传进度条（axios onUploadProgress）、取消/确认按钮
- [x] 12.2 实现上传成功处理：显示成功消息，刷新任务列表
- [x] 12.3 实现上传失败处理：显示具体错误消息，允许重试
- [x] 12.4 在 `TaskList.vue` 中集成 `UploadDialog`：点击"上传结果"按钮弹出对话框

## 13. 前端：报告页兼容

- [x] 13.1 修改 `ReportView.vue`：根据 `ReportData.connectionType` 显示执行模式标签
- [x] 13.2 线下回放报告顶部显示"执行模式：线下回放"标签（与"执行模式：实时检测"样式区分）

## 14. 集成与验证

- [x] 14.1 端到端验证：创建线下任务 → 下载脚本 → 在测试容器中执行 → 上传结果 → 平台回放分析 → 查看报告和日志
- [x] 14.2 验证 Skill 合并：上传包含新 executionContext 的结果包，确认平台 Skill 库正确更新
- [x] 14.3 验证边界情况：ZIP 格式错误、token 重复使用、超大文件上传、服务重启恢复
- [x] 14.4 确保现有在线检测（SSH/Kubectl）功能不受影响（回归测试）
