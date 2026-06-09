## 1. 后端枚举与状态转换

- [x] 1.1 在 `TaskStatus.java` 中新增 `INTERRUPTED` 枚举值
- [x] 1.2 在 `TaskManagementService.init()` 加载任务后，遍历 `taskStore` 将 `RUNNING`/`CREATED` 状态转为 `INTERRUPTED`，设置 `errorMessage` 并持久化

## 2. 前端适配

- [x] 2.1 在 `StatusBadge.vue` 中新增 `badge-interrupted` 样式（灰色或橙色）

## 3. 验证

- [x] 3.1 启动服务创建任务，直接关闭，重启后确认任务列表中该任务显示为 `INTERRUPTED`
