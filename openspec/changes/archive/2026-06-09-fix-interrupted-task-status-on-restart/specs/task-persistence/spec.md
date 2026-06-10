## 新增需求

### 需求: 服务启动时必须将未完成的任务标记为中断

系统在服务启动恢复任务存储时，必须将状态为 `RUNNING` 或 `CREATED` 的任务自动转为 `INTERRUPTED`，以反映检测流程因服务重启而中断的事实。

#### 场景: RUNNING 任务重启后标记为 INTERRUPTED

- **当** 服务重启且磁盘上存在状态为 `RUNNING` 的任务
- **那么** `TaskManagementService.init()` 必须将该任务的状态更新为 `INTERRUPTED`
- **且** 必须设置 `errorMessage` 为 "服务重启，检测中断"
- **且** 必须将更新后的任务持久化到磁盘

#### 场景: CREATED 任务重启后也标记为 INTERRUPTED

- **当** 服务重启且磁盘上存在状态为 `CREATED` 但尚未开始执行的任务
- **那么** `init()` 必须同样将其标记为 `INTERRUPTED`，因为异步检测流程已丢失

#### 场景: COMPLETED 和 FAILED 任务不受影响

- **当** 服务重启且任务状态为 `COMPLETED` 或 `FAILED`
- **那么** `init()` 必须保持其状态不变
