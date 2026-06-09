## 新增需求

### 需求: 命令级进化

当阶段一 AI 判定返回 `EVOLVE` 时，系统必须使用 `nextCommand` 字段中的新命令重新在容器中执行，并提交给 AI 再次判定。单条命令最多进化 5 轮（可配置）。

#### 场景: 一次进化后成功

- **当** 原始命令 `capsh --print` 返回 `command not found`，AI 返回 `EVOLVE + nextCommand="cat /proc/1/status | grep CapEff"`，新命令执行成功并得到 PASS 判定
- **那么** 系统将新命令追加到当前 context 的 detectionCommands，标记 commandEvolved=true，versionTimestamp 更新，evolutionCount+1，生成新的 Skill 文件

#### 场景: 进化轮次超限

- **当** 某条命令连续 5 轮进化后仍未得到 PASS 或 FAIL 终局判定
- **那么** 系统记录 WARN 日志，标记该命令为 INCONCLUSIVE，跳过继续下一条命令

#### 场景: 进化后 FAIL

- **当** 进化后的命令执行成功且 AI 判定为 FAIL
- **那么** 系统同样将进化后的命令持久化到 Skill 中（因为它是有效的检测命令，只是检测到了安全漏洞），最终状态标记为 FAIL

### 需求: 上下文级进化

当程序侧无法匹配任何 executionContext，或检测过程中 AI 连续返回 ENV_MISMATCH 时，系统必须调用专用的上下文进化 Prompt，由 AI 为当前环境生成一套全新的 executionContext。

#### 场景: 无匹配 Context 触发进化

- **当** 程序侧 `selectBestContext()` 返回 empty，且 `contextExistsForEnvironment()` 返回 false
- **那么** 系统调用上下文进化 AI（传入 Skill 定义 + 目标环境指纹 + 原始探针输出），AI 返回包含 environmentFingerprint + envCheckCommands + executionLogic 的完整 ExecutionContext 对象

#### 场景: AI 生成的新 Context 校验通过

- **当** AI 返回的 ExecutionContext 包含所有必填字段，且 envCheckCommands 和 detectionCommands 均非空
- **那么** 系统将该 Context 追加到 Skill 的 executionContexts 数组，设置 evolvedFrom="ai-generated"，evolvedAt=当前时间戳，使用新 Context 重新执行阶段一检测

#### 场景: AI 生成的 Context 校验失败

- **当** AI 返回的 JSON 缺少必填字段或字段类型不正确
- **那么** 系统调用 Jackson 反序列化 → 失败 → JSON 清洗 → AI 自我修复 → 再次反序列化 → 仍失败则返回 null，Task 标记该 Skill 检测为 FAILED

### 需求: 进化去重

系统在保存新生成的 executionContext 之前，必须检查 Skill 中是否已存在相同环境指纹的 context（osType + osFlavor 完全匹配且 requiredTools 完全子集），避免生成重复 Context。

#### 场景: 去重拦截

- **当** AI 生成了一个新的 executionContext，但该 Skill 中已存在一个相同 osType 和 osFlavor 的 context
- **那么** 系统跳过追加，直接选择已有 context 继续检测，记录 INFO 日志

#### 场景: 无重复则追加

- **当** AI 生成的 Context 的环境指纹与现有所有 Context 均不重复
- **那么** 系统将该 Context 追加到 Skill 的 executionContexts 数组末尾

### 需求: 失败 Context 标记

当 AI 生成的 executionContext 在实际检测中失败（连续 ENV_MISMATCH 或命令全部超时），系统仍必须持久化该 Context，但标记 `deprecated: true`，避免后续对相同环境重复触发上下文进化。

#### 场景: 失败 Context 标记并持久化

- **当** AI 生成的 Context 中所有 detectionCommands 执行均超时或返回错误
- **那么** 系统将该 Context 的 deprecated 字段设为 true，追加到 Skill 中，持久化到磁盘

#### 场景: 已弃用 Context 不被匹配

- **当** `selectBestContext()` 遍历 executionContexts
- **那么** 系统跳过所有 `deprecated: true` 的 Context

### 需求: AI JSON 自我修复

当 AI 返回的 JSON 无法被 Jackson 解析时，系统必须先尝试通过 `JsonCleaner.clean()` 清洗（去 Markdown 包裹、截取 `{...}` 边界），清洗后仍失败则调用 AI 自我修复 Prompt，让 AI 修正格式错误。

#### 场景: Markdown 包裹清洗成功

- **当** AI 返回 ````json\n{"status": "PASS"...}\n````
- **那么** `JsonCleaner.clean()` 正则移除 Markdown 标记，提取纯 JSON，Jackson 解析成功

#### 场景: AI 自我修复成功

- **当** 清洗后的 JSON 仍解析失败（如缺少引号、尾部逗号）
- **那么** 系统调用 `selfRepairJson()`，将损坏的 JSON 和目标类型名提交给 AI，AI 返回修复后的合法 JSON，Jackson 解析成功

#### 场景: AI 自我修复也失败

- **当** 调用 AI 自我修复后 Jackson 仍然解析失败
- **那么** 系统抛出 `AiResponseParseException`，原始返回值和清洗后的文本均记录到 ERROR 日志
