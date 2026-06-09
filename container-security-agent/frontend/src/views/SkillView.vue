<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
      <h2>Skill 管理</h2>
      <button class="btn btn-sm btn-secondary" @click="reload" :disabled="loading">🔄 重新加载</button>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="skills.length === 0" class="empty-state">
      <div class="icon">🧩</div>
      <p>暂无 Skill，请在 skills/ 目录下添加 Skill JSON 文件</p>
    </div>
    <div v-else class="skill-list">
      <div v-for="skill in skills" :key="skill.skillId" class="card skill-item">
        <div class="skill-header" @click="toggleSkill(skill.skillId)">
          <div class="skill-summary">
            <strong>{{ skill.skillName }}</strong>
            <code class="skill-id-tag">{{ skill.skillId }}</code>
            <RiskLevelTag :level="skill.riskLevel" />
            <span class="meta-tag">{{ skill.contextCount }} 个 Context</span>
            <span class="meta-tag">进化 {{ skill.evolutionCount }} 次</span>
          </div>
          <span class="expand-icon">{{ expanded[skill.skillId] ? '▼' : '▶' }}</span>
        </div>

        <div v-if="expanded[skill.skillId]" class="skill-detail">
          <p class="skill-desc">{{ skill.description }}</p>

          <!-- Contexts -->
          <h4 style="margin: 16px 0 8px;">执行上下文 (ExecutionContexts)</h4>
          <div v-if="contextCache[skill.skillId]?.length">
            <div v-for="ctx in contextCache[skill.skillId]" :key="ctx.contextId" class="context-card">
              <div class="context-header">
                <strong>{{ ctx.contextId }}</strong>
                <span v-if="ctx.deprecated" class="deprecated-tag">已弃用</span>
                <span v-if="ctx.evolvedFrom" class="evolved-tag">进化自: {{ ctx.evolvedFrom }}</span>
              </div>
              <div v-if="ctx.environmentFingerprint" class="context-env">
                <span class="env-chip">OS: {{ ctx.environmentFingerprint.osType }}</span>
                <span class="env-chip" v-if="ctx.environmentFingerprint.osFlavors">
                  Flavor: {{ ctx.environmentFingerprint.osFlavors.join(', ') }}
                </span>
                <span class="env-chip">Shell: {{ ctx.environmentFingerprint.shellType || 'N/A' }}</span>
                <span class="env-chip" v-if="ctx.environmentFingerprint.requiredTools">
                  工具: {{ ctx.environmentFingerprint.requiredTools.join(', ') }}
                </span>
              </div>
              <div v-if="ctx.envCheckCommands?.length" class="context-section">
                <strong style="font-size: 12px;">环境检查命令：</strong>
                <code v-for="cmd in ctx.envCheckCommands" :key="cmd" class="cmd-pill">{{ cmd }}</code>
              </div>
              <div v-if="ctx.executionLogic" class="context-section">
                <strong style="font-size: 12px;">检测命令 ({{ ctx.executionLogic.detectionCommands?.length || 0 }} 条)：</strong>
                <code v-for="cmd in ctx.executionLogic.detectionCommands" :key="cmd" class="cmd-pill">{{ cmd }}</code>
              </div>
            </div>
          </div>
          <div v-else class="empty-state" style="padding: 20px;">加载 Context 中...</div>

          <!-- Evolution History -->
          <h4 style="margin: 16px 0 8px;">进化历史</h4>
          <div v-if="historyCache[skill.skillId]?.length">
            <div v-for="(ver, i) in historyCache[skill.skillId]" :key="i" class="history-item">
              <span class="history-time">{{ formatTimestamp(ver.versionTimestamp) }}</span>
              <span class="history-ver">v{{ ver.evolutionCount }}</span>
              <span>{{ ver.executionContexts?.length || 0 }} 个 Context</span>
            </div>
          </div>
          <div v-else style="font-size: 13px; color: #9ca3af; padding: 8px;">加载历史中...</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { listSkills, getSkill, getSkillHistory } from '../api'
import RiskLevelTag from '../components/RiskLevelTag.vue'

const skills = ref([])
const loading = ref(true)
const expanded = reactive({})
const contextCache = reactive({})
const historyCache = reactive({})

onMounted(async () => {
  await fetchSkills()
})

async function fetchSkills() {
  loading.value = true
  try {
    const { data } = await listSkills()
    skills.value = data
  } catch (e) {
    console.error('加载 Skill 列表失败', e)
  } finally {
    loading.value = false
  }
}

async function toggleSkill(skillId) {
  if (expanded[skillId]) {
    expanded[skillId] = false
    return
  }
  expanded[skillId] = true

  // Load full skill detail
  if (!contextCache[skillId]) {
    try {
      const { data } = await getSkill(skillId)
      contextCache[skillId] = data.executionContexts || []
    } catch {
      contextCache[skillId] = []
    }
  }

  // Load evolution history
  if (!historyCache[skillId]) {
    try {
      const { data } = await getSkillHistory(skillId)
      historyCache[skillId] = data
    } catch {
      historyCache[skillId] = []
    }
  }
}

async function reload() {
  try {
    const { default: api } = await import('../api')
    await api.post('/skills/reload')
    expanded.value = {}
    Object.keys(contextCache).forEach(k => delete contextCache[k])
    Object.keys(historyCache).forEach(k => delete historyCache[k])
    await fetchSkills()
  } catch (e) {
    console.error('重载失败', e)
  }
}

function formatTimestamp(ts) {
  if (!ts) return 'N/A'
  try {
    return new Date(ts).toLocaleString('zh-CN')
  } catch {
    return String(ts)
  }
}
</script>

<style scoped>
.skill-item { padding: 0; overflow: hidden; }
.skill-header { display: flex; justify-content: space-between; align-items: center; padding: 16px 20px; cursor: pointer; user-select: none; }
.skill-header:hover { background: #f9fafb; }
.skill-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.skill-id-tag { font-size: 12px; color: #9ca3af; }
.meta-tag { font-size: 11px; background: #f3f4f6; padding: 1px 6px; border-radius: 4px; color: #6b7280; }
.expand-icon { font-size: 12px; color: #9ca3af; }
.skill-detail { padding: 0 20px 20px; border-top: 1px solid #e5e7eb; }
.skill-desc { font-size: 14px; color: #6b7280; margin-top: 12px; }
.context-card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 12px; margin-bottom: 8px; }
.context-header { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
.deprecated-tag { font-size: 11px; background: #fef2f2; color: #dc2626; padding: 1px 6px; border-radius: 3px; }
.evolved-tag { font-size: 11px; background: #ede9fe; color: #6b21a8; padding: 1px 6px; border-radius: 3px; }
.context-env { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 8px; }
.env-chip { font-size: 11px; background: #eff6ff; color: #1e40af; padding: 2px 6px; border-radius: 4px; }
.context-section { margin-top: 8px; display: flex; flex-wrap: wrap; align-items: center; gap: 4px; }
.cmd-pill { font-size: 11px; background: #1e293b; color: #e2e8f0; padding: 2px 8px; border-radius: 4px; font-family: monospace; }
.history-item { display: flex; gap: 12px; align-items: center; padding: 8px 12px; font-size: 13px; border-bottom: 1px solid #f3f4f6; }
.history-time { color: #6b7280; min-width: 160px; }
.history-ver { font-weight: 600; color: #374151; min-width: 60px; }
</style>
