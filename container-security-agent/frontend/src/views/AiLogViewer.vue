<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
      <div>
        <h2>AI 交互日志 — {{ taskId }}</h2>
        <small style="color: #6b7280;">{{ totalElements }} 条记录</small>
      </div>
      <div style="display: flex; gap: 8px;">
        <button class="btn btn-sm btn-secondary" @click="refresh">刷新</button>
        <router-link :to="`/report/${taskId}`" class="btn btn-sm btn-primary">查看报告</router-link>
        <router-link to="/tasks" class="btn btn-sm btn-secondary">← 返回</router-link>
      </div>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="entries.length === 0" class="empty-state">
      <div class="icon">[Log]</div>
      <p>暂无 AI 交互日志</p>
    </div>
    <div v-else class="log-list">
      <div v-for="entry in entries" :key="entry.logId" class="card log-card"
           :style="{ borderLeft: `4px solid ${phaseColor(entry.phase)}` }">
        <div class="log-header" @click="toggle(entry.logId)">
          <div class="log-meta">
            <span class="phase-tag" :style="{ background: phaseColor(entry.phase), color: '#fff' }">
              {{ phaseLabel(entry.phase) }}
            </span>
            <code class="skill-id-text">{{ entry.skillId }}</code>
            <span class="log-time">{{ formatTime(entry.timestamp) }}</span>
            <span class="log-cost">{{ entry.costMs }}ms</span>
            <span class="parse-tag" :class="'parse-' + entry.parseResult.toLowerCase()">
              {{ entry.parseResult }}
            </span>
            <span class="target-class">{{ entry.targetClass }}</span>
          </div>
          <span class="expand-icon">{{ expanded[entry.logId] ? '▼' : '▶' }}</span>
        </div>

        <div v-if="expanded[entry.logId]" class="log-body">
          <div class="tabs">
            <button :class="['tab', { active: activeTab[entry.logId] === 'system' }]"
                    @click="activeTab[entry.logId] = 'system'">System Prompt</button>
            <button :class="['tab', { active: activeTab[entry.logId] === 'user' }]"
                    @click="activeTab[entry.logId] = 'user'">User Prompt</button>
            <button :class="['tab', { active: activeTab[entry.logId] === 'response' }]"
                    @click="activeTab[entry.logId] = 'response'">Response</button>
          </div>

          <div v-show="activeTab[entry.logId] === 'system'" class="tab-content">
            <pre class="prompt-pre">{{ entry.systemPrompt || '(空)' }}</pre>
          </div>
          <div v-show="activeTab[entry.logId] === 'user'" class="tab-content">
            <pre class="prompt-pre">{{ entry.userPrompt || '(空)' }}</pre>
          </div>
          <div v-show="activeTab[entry.logId] === 'response'" class="tab-content">
            <div class="response-section">
              <h5>原始响应</h5>
              <pre class="raw-response">{{ entry.rawResponse || '(空)' }}</pre>
            </div>
            <div class="response-section" v-if="entry.cleanedJson">
              <h5>清洗后 JSON</h5>
              <pre class="cleaned-json">{{ formatJson(entry.cleanedJson) }}</pre>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="pagination" v-if="totalPages > 1">
      <button :disabled="page <= 0" @click="page--">上一页</button>
      <span>{{ page + 1 }} / {{ totalPages }}</span>
      <button :disabled="page >= totalPages - 1" @click="page++">下一页</button>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, watch } from 'vue'
import { fetchAiLogs } from '../api'

const props = defineProps({ taskId: { type: String, required: true } })

const entries = ref([])
const loading = ref(true)
const page = ref(0)
const totalPages = ref(0)
const totalElements = ref(0)
const expanded = reactive({})
const activeTab = reactive({})

async function load() {
  loading.value = true
  try {
    const { data } = await fetchAiLogs(props.taskId, null, page.value, 50)
    entries.value = data.content || []
    totalPages.value = data.totalPages || 0
    totalElements.value = data.totalElements || 0
  } catch (e) {
    console.error('加载 AI 日志失败', e)
  } finally {
    loading.value = false
  }
}

function refresh() {
  page.value = 0
  load()
}

function toggle(logId) {
  if (expanded[logId]) {
    expanded[logId] = false
    return
  }
  expanded[logId] = true
  if (!activeTab[logId]) activeTab[logId] = 'system'
}

function phaseColor(phase) {
  return {
    phase0: '#3b82f6', phase1: '#22c55e', 'context-evolution': '#8b5cf6',
    phase2: '#f59e0b', 'json-repair': '#eab308',
    'ssh-connection-error': '#dc2626'
  }[phase] || '#9ca3af'
}

function phaseLabel(phase) {
  return {
    phase0: '阶段零·匹配', phase1: '阶段一·判定', 'context-evolution': '上下文进化',
    phase2: '阶段二·报告', 'json-repair': 'JSON 修复',
    'ssh-connection-error': 'SSH 连接失败'
  }[phase] || phase
}

function formatTime(ts) {
  try { return new Date(ts).toLocaleString('zh-CN') } catch { return ts }
}

function formatJson(str) {
  try { return JSON.stringify(JSON.parse(str), null, 2) } catch { return str }
}

watch(page, () => load())
onMounted(load)
</script>

<style scoped>
.log-list { display: flex; flex-direction: column; gap: 12px; }
.log-card { padding: 0; overflow: hidden; }
.log-header { display: flex; justify-content: space-between; align-items: center; padding: 14px 18px; cursor: pointer; user-select: none; }
.log-header:hover { background: #f9fafb; }
.log-meta { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.phase-tag { font-size: 11px; padding: 3px 10px; border-radius: 999px; font-weight: 600; white-space: nowrap; }
.skill-id-text { font-size: 12px; color: #6b7280; }
.log-time { font-size: 12px; color: #9ca3af; }
.log-cost { font-size: 12px; color: #6b7280; font-weight: 500; }
.parse-tag { font-size: 11px; padding: 2px 8px; border-radius: 4px; font-weight: 600; }
.parse-success { background: #dcfce7; color: #166534; }
.parse-repair_success { background: #fefce8; color: #a16207; }
.parse-failed { background: #fef2f2; color: #991b1b; }
.target-class { font-size: 11px; color: #9ca3af; }
.expand-icon { font-size: 12px; color: #9ca3af; flex-shrink: 0; }
.log-body { border-top: 1px solid #e5e7eb; }
.tabs { display: flex; border-bottom: 1px solid #e5e7eb; }
.tab { padding: 10px 20px; border: none; background: none; font-size: 13px; cursor: pointer; color: #6b7280; border-bottom: 2px solid transparent; transition: all .15s; }
.tab:hover { color: #374151; }
.tab.active { color: #1d4ed8; border-bottom-color: #1d4ed8; }
.tab-content { padding: 16px; }
.prompt-pre { background: #1e293b; color: #e2e8f0; padding: 14px; border-radius: 8px; font-size: 12px; max-height: 400px; overflow: auto; white-space: pre-wrap; word-break: break-all; }
.response-section { margin-bottom: 14px; }
.response-section h5 { font-size: 12px; color: #6b7280; margin-bottom: 4px; }
.raw-response { background: #f3f4f6; color: #374151; padding: 12px; border-radius: 6px; font-size: 12px; max-height: 200px; overflow: auto; white-space: pre-wrap; word-break: break-all; }
.cleaned-json { background: #f0fdf4; color: #166534; padding: 12px; border-radius: 6px; font-size: 12px; max-height: 300px; overflow: auto; white-space: pre; }
</style>
