## 为什么

1. **进化次数显示错误**：`saveEvolvedSkill()` 和 `DetectionOrchestrator` 各对 `evolutionCount` 递增一次，导致每次进化实际 +2。此外，原始 skill 文件中预置了 `evolvedFrom: "linux-debian-ubuntu-standard"` 元数据（非运行时产生），但 `evolutionCount=0` 是正确的一一上下文是初始定义的，非运行时进化生成。

2. **创建任务页 Skill 默认未全选**：`form.skillIds` 初始为空数组，用户需逐个勾选，操作繁琐。

3. **Skill 勾选框布局不合理**：复选框占据宽度比例过大，描述文字被挤压，且说明信息过于简略。

## 变更内容

- 修复 `saveEvolvedSkill()` 中重复递增 `evolutionCount` 的问题
- `CreateTask.vue` 加载 Skill 后默认全选
- `CreateTask.vue` 改进 Skill 行布局：增加描述文字显示，调整 checkbox 宽度

## 功能 (Capabilities)

### 新增功能

无

### 修改功能

- `skill-management`: 修复进化次数重复递增
- `management-ui`: 创建任务页 Skill 默认全选 + 优化布局

## 影响

| 范围 | 详情 |
|---|---|
| `SkillLoaderService.java` | 删除 `saveEvolvedSkill()` 中的 `setEvolutionCount(+1)` |
| `CreateTask.vue` | 默认全选 + 布局优化 |
| 破坏性 | 无 |
