## 上下文

`TaskManagementService` 通过 `AtomicInteger dailySeq` 和 `String currentDate` 两个字段协作生成跨日自增的任务 ID（`TASK-{yyyyMMdd}-{seq}`）。服务启动时，`init()` 从磁盘扫描已有的 `*.task.json` 文件恢复内存中的任务存储和当日序号计数器。

当前问题：`init()` 正确恢复了 `dailySeq`，但未同步设置 `currentDate`。`generateTaskId()` 中的跨日检查 `!today.equals(currentDate)` 在 `currentDate` 为 `""` 时恒为真，导致计数器被错误清零。

## 目标 / 非目标

**目标：**
- 确保重启后第一个新任务 ID 的序号从磁盘已有最大值之后开始
- 最小化改动，不引入新的状态变量或复杂逻辑

**非目标：**
- 不改变 ID 生成算法（如改用 UUID）
- 不引入数据库序列或分布式 ID 方案
- 不改变文件存储结构

## 决策

### 方案：在 `init()` 中同步设置 `currentDate`

在 `init()` 恢复计数器的位置，增加一行 `currentDate = today;`。

```java
// 第 75 行之后插入：
dailySeq.set((int) todayCount);
this.currentDate = today;  // 新增
```

**理由**：`init()` 已经拿到了 `today` 变量（第 75 行），直接复用即可。`currentDate` 的唯一用途就是配合 `dailySeq` 做跨日重置判断——二者状态本就应当绑定维护。

**考虑过的替代方案**：

| 方案 | 评估 |
|---|---|
| A: `init()` 中设置 `currentDate = today` | ✅ 最简方案，一行修复，语义直接 |
| B: 在 `generateTaskId()` 首次调用时从磁盘统计最大序号 | ❌ 每次调用都读磁盘；`init()` 已做此工作，重复劳动 |
| C: 移除 `currentDate`，改用 LocalDate 直接判断 | ❌ 不能解决序号恢复问题；仍需要从磁盘读取当前计数 |
| D: 使用文件锁/原子写入防覆盖 | ❌ 治标不治本，ID 仍然从 1 开始只是不覆盖旧文件，会导致日志散落两个目录 |

## 风险 / 权衡

- **多实例并发**：当前设计假设单实例运行（内存 `ConcurrentHashMap` + 文件存储），多实例会竞争同一 ID。此修复未引入新的并发风险——原有设计也不支持多实例。
- **时钟回拨**：如果系统时间被人为调整到过去的日期，`generateTaskId()` 会正确识别 `currentDate` 变更并重置计数器——这是预期行为。
- **文件手动删除**：如果用户手动删除 `.task.json` 文件但保留日志目录，序号可能回退。此场景属于运维操作不规范的后果，非代码缺陷。
