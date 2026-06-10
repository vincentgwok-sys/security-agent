## 新增需求

### 需求: 线下执行 Skill 合并

系统必须在回放分析开始前，将上传结果包中 `evolved_skills/` 目录下的 Skill 文件与平台现有 Skill 合并。

#### 场景: 上传 Skill 无冲突时追加

- **当** 上传的 Skill 文件包含一个平台不存在的 `executionContext`（osType + osFlavor 组合与所有现有 Context 均不重复，且 requiredTools 不完全是现有 Context 的超集/子集等效匹配）
- **那么** 系统将新 Context 追加到对应 Skill 的 `executionContexts` 数组末尾，标记 `evolvedFrom="offline-execution"`、`evolvedAt=当前时间戳`

#### 场景: 上传 Skill 与已有 Context 重复时跳过

- **当** 上传的 `executionContext` 与平台已有 Context 存在精确重复（osType + osFlavor 完全匹配 且 requiredTools 完全子集关系）
- **那么** 系统跳过该 Context，记录 INFO 日志，不追加

#### 场景: 无冲突合并后持久化

- **当** 合并产生了新的 `executionContext`
- **那么** 系统将更新后的 `SkillDefinition`（evolutionCount +1）持久化为新文件 `{skillId}-{newTimestamp}.json`（复用 `SkillLoaderService.saveEvolvedSkill`），旧文件保留

#### 场景: 上传 Skill 仅有已存在的 Context

- **当** 上传 Skill 的所有 `executionContext` 均与平台已有 Context 重复
- **那么** 系统不生成新 Skill 文件，不增加 evolutionCount，记录 INFO 日志

#### 场景: 上传的 Skill ID 在平台不存在

- **当** 上传 Skill 的 `skillId` 在平台 Skill 库中不存在
- **那么** 系统将该 Skill 作为全新 Skill 持久化到 `skills/` 目录，文件名格式为 `{skillId}-{currentTimeMillis}.json`

### 需求: 合并去重规则

去重比较必须基于 `osType + osFlavor` 精确匹配和 `requiredTools` 子集关系。

#### 场景: osType + osFlavor 完全相同且 requiredTools 是子集

- **当** 平台已有 Context 的 requiredTools 为 `["cat", "grep", "mount", "capsh"]`，上传 Context 的 requiredTools 为 `["cat", "grep", "mount"]`（是已有 Context 的子集）
- **那么** 系统判定为重复，跳过上传的 Context

#### 场景: osType 相同但 osFlavor 不同

- **当** 平台已有 Context 的 osFlavors 为 `["debian", "ubuntu"]`，上传 Context 的 osFlavors 为 `["alpine"]`
- **那么** 系统判定不重复，追加上传的 Context

#### 场景: osType 不同

- **当** 平台已有 Context 的 osType 为 `"linux"`，上传 Context 的 osType 为 `"windows"`
- **那么** 系统判定不重复，追加上传的 Context

### 需求: 进化标记区分

线下执行产生的进化 Context 必须与在线进化产生的 Context 有不同的来源标记。

#### 场景: 线上进化标记

- **当** 在线检测中 AI 生成新 Context
- **那么** `evolvedFrom` 字段值为 `"ai-generated"`

#### 场景: 线下进化标记

- **当** 上传结果中的 Skill 被合并到平台
- **那么** 新 Context 的 `evolvedFrom` 字段值为 `"offline-execution"`

#### 场景: 进化历史查询可追溯来源

- **当** 请求 `GET /api/skills/{skillId}/history`
- **那么** 每个 Context 的 `evolvedFrom` 字段明确显示其来源（`ai-generated` / `offline-execution` / `manual` 等）
