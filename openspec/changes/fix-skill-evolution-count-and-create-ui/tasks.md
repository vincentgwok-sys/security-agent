## 1. 进化次数修复

- [x] 1.1 删除 `SkillLoaderService.saveEvolvedSkill()` 中重复的 `setEvolutionCount(+1)`，避免每次进化实际 +2
- [x] 1.2 在 `SkillView.vue` 中区分运行时进化（`evolvedFrom === 'ai-generated'`）与初始元数据，避免误导

## 2. 创建任务页默认全选

- [x] 2.1 `CreateTask.vue` 加载 Skill 后自动设置 `form.skillIds` 为所有 skillId
- [x] 2.2 `resetForm()` 重置时同样恢复全选

## 3. 创建任务页 Skill 布局优化

- [x] 3.1 每行增加 Skill 描述文字（`skill.description`），限制最多 2 行
- [x] 3.2 优化 CSS：checkbox 不缩放、描述区 `flex: 1`、max-height 从 240px 增到 360px

## 4. 验证

- [ ] 4.1 验证进化触发后 `evolutionCount` 每次增加 1（非 2）
- [ ] 4.2 创建任务页打开后所有 Skill 默认勾选
- [ ] 4.3 创建任务页 Skill 行显示完整描述信息
