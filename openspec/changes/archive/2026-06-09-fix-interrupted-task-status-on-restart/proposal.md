## 为什么

服务重启后，所有正在异步执行中的检测任务（SSH 连接、线程池）均已终止，但磁盘上保存的任务状态仍为 `RUNNING`。重启后这些任务在管理界面显示为"检测中"，但实际上永远不会完成，给用户错误的预期。

## 变更内容

- 新增 `INTERRUPTED` 任务状态
- `TaskManagementService.init()` 恢复任务时将 `RUNNING` 和 `CREATED` 状态自动转为 `INTERRUPTED`，并持久化到磁盘
- 前端 `StatusBadge` 组件新增 `INTERRUPTED` 样式

## 功能 (Capabilities)

### 新增功能

无

### 修改功能

- `task-persistence`: 新增需求——服务启动时必须将未完成的任务标记为中断状态

## 影响

| 范围 | 详情 |
|---|---|
| 代码 | `TaskStatus.java`（新增枚举值）、`TaskManagementService.java`（init 中标记中断）、`StatusBadge.vue`（新增样式） |
| 规范 | `openspec/specs/task-persistence/spec.md` — 新增重启中断场景 |
| API | 无变更（新增的 `INTERRUPTED` 状态通过现有接口返回） |
| 破坏性 | 无 |
