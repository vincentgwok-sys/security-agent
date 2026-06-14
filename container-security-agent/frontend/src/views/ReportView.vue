<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
      <h2>检测报告 — {{ taskId }}</h2>
      <div style="display: flex; gap: 8px;">
        <button class="btn btn-sm btn-secondary" @click="loadJson" v-if="report">
          查看 JSON
        </button>
        <button class="btn btn-sm btn-primary" @click="window.print()" v-if="report">
          打印
        </button>
        <router-link to="/tasks" class="btn btn-sm btn-secondary">← 返回</router-link>
      </div>
    </div>

    <div v-if="loading" class="loading">加载报告...</div>
    <div v-else-if="!report" class="empty-state">
      <div class="icon">[Report]</div>
      <p>报告未找到或检测尚未完成</p>
      <router-link to="/tasks" class="btn btn-primary" style="margin-top: 12px;">返回任务列表</router-link>
    </div>
    <template v-else>
      <!-- Execution mode label -->
      <div v-if="report.connectionType" class="exec-mode-label" :class="'mode-' + report.connectionType">
        {{ report.connectionType === 'offline' ? '[DL] 执行模式：线下回放' :
           report.connectionType === 'kubectl' ? '[K8s] 执行模式：Kubectl 跳板' : '[SSH] 执行模式：实时检测' }}
      </div>

      <!-- Score Section -->
      <div class="card score-card">
        <div class="score-main">
          <div class="score-ring-wrapper">
            <svg viewBox="0 0 120 120" class="score-svg">
              <circle cx="60" cy="60" r="52" fill="none" stroke="#e5e7eb" stroke-width="10" />
              <circle cx="60" cy="60" r="52" fill="none" :stroke="scoreColor" stroke-width="10"
                :stroke-dasharray="circumference" :stroke-dashoffset="scoreOffset"
                stroke-linecap="round" transform="rotate(-90 60 60)" class="score-arc" />
              <text x="60" y="52" text-anchor="middle" font-size="26" font-weight="bold" :fill="scoreColor">{{ report.overallScore }}</text>
              <text x="60" y="68" text-anchor="middle" font-size="11" fill="#6b7280">得分</text>
            </svg>
          </div>
          <div class="score-stats">
            <div class="score-stat">
              <span class="stat-label">通过率</span>
              <span class="stat-value">{{ report.passRate }}</span>
            </div>
            <div class="score-stat">
              <span class="stat-label">目标 IP</span>
              <span class="stat-value">{{ report.targetIp }}</span>
            </div>
            <div class="score-stat" v-if="report.targetEnvironment">
              <span class="stat-label">目标环境</span>
              <span class="stat-value">{{ report.targetEnvironment.osType || 'Unknown' }}</span>
            </div>
            <div class="score-stat">
              <span class="stat-label">审计时间</span>
              <span class="stat-value" style="font-size: 14px;">{{ report.auditTime }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Skill Reports -->
      <h3 style="margin: 24px 0 12px;">检测结果明细</h3>
      <div v-if="!report.skillReports || report.skillReports.length === 0" class="empty-state">
        <p>无检测结果</p>
      </div>
      <div v-else class="skill-cards">
        <div v-for="sr in report.skillReports" :key="sr.skillId" class="card skill-card">
          <div class="skill-card-header" :style="{ borderLeft: `4px solid ${sr.finalStatus === 'PASS' ? '#22c55e' : '#ef4444'}` }">
            <div>
              <StatusBadge :status="sr.finalStatus" />
              <strong style="margin-left: 8px;">{{ sr.skillName }}</strong>
              <code style="margin-left: 8px; font-size: 12px; color: #9ca3af;">{{ sr.skillId }}</code>
            </div>
            <span class="context-tag">{{ sr.contextEnvironment }}</span>
          </div>
          <div class="skill-card-body">
            <!-- Test Report -->
            <div v-if="sr.testReport" style="margin-bottom: 16px;">
              <h4 style="font-size: 14px; margin-bottom: 8px;">[Analyze] 检测分析</h4>
              <p style="font-size: 14px; color: #374151;">{{ sr.testReport.summary }}</p>
              <RiskLevelTag v-if="sr.testReport.riskLevel" :level="sr.testReport.riskLevel" />
              <div v-if="sr.testReport.evidence" style="margin-top: 8px;">
                <strong style="font-size: 12px;">关键证据：</strong>
                <pre class="evidence-pre">{{ sr.testReport.evidence }}</pre>
              </div>
            </div>

            <!-- Remediation -->
            <div v-if="sr.securityRemediation" class="remediation-block">
              <h4 style="font-size: 14px; margin-bottom: 8px;">[Fix] 修复建议</h4>
              <p v-if="sr.securityRemediation.strategy" style="font-size: 13px; margin-bottom: 8px;">
                <strong>策略：</strong>{{ sr.securityRemediation.strategy }}
              </p>
              <div v-if="sr.securityRemediation.k8sYamlPatch">
                <strong style="font-size: 12px;">K8s YAML 补丁：</strong>
                <pre class="yaml-pre">{{ sr.securityRemediation.k8sYamlPatch }}</pre>
              </div>
              <p v-if="sr.securityRemediation.alternativeAdvice" style="font-size: 13px; margin-top: 4px;">
                <strong>替代方案：</strong>{{ sr.securityRemediation.alternativeAdvice }}
              </p>
            </div>

            <!-- Evolution -->
            <div v-if="sr.isEvolved" class="evolution-badge">
              ⚡ 此 Skill 在检测中发生了{{ sr.evolutionType === 'context' ? '上下文级' : '命令级' }}进化
            </div>

            <!-- Execution Records -->
            <details v-if="sr.executionRecords?.length" style="margin-top: 12px;">
              <summary style="cursor: pointer; font-size: 13px; color: #6b7280;">
                执行记录 ({{ sr.executionRecords.length }} 条命令)
              </summary>
              <CommandOutput
                v-for="(rec, i) in sr.executionRecords"
                :key="i"
                :command="rec.command"
                :stdout="rec.result?.stdout"
                :stderr="rec.result?.stderr"
                :exitCode="rec.result?.exitCode" />
            </details>
          </div>
        </div>
      </div>

      <!-- Timeline -->
      <h3 style="margin: 24px 0 12px;">执行时间线</h3>
      <div class="timeline" v-if="timeline.length > 0">
        <div v-for="(item, i) in timeline" :key="i" class="timeline-item">
          <div class="timeline-dot" :class="tlClass(item.status)"></div>
          <div class="timeline-card">
            <span class="tl-skill">{{ item.skillName }}</span>
            <code class="tl-cmd">$ {{ item.command }}</code>
            <span class="tl-status">→ {{ item.status }}</span>
          </div>
        </div>
      </div>
    </template>

    <!-- JSON modal -->
    <div v-if="showJson" class="modal-overlay" @click.self="showJson = false">
      <div class="modal-content">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
          <h3>报告 JSON</h3>
          <button class="btn btn-sm btn-secondary" @click="showJson = false">关闭</button>
        </div>
        <pre class="json-pre">{{ jsonText }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getJsonReport } from '../api'
import StatusBadge from '../components/StatusBadge.vue'
import RiskLevelTag from '../components/RiskLevelTag.vue'
import CommandOutput from '../components/CommandOutput.vue'

const props = defineProps({ taskId: { type: String, required: true } })

const report = ref(null)
const loading = ref(true)
const showJson = ref(false)
const jsonText = ref('')

const circumference = 2 * Math.PI * 52

const scoreColor = computed(() => {
  const s = report.value?.overallScore ?? 0
  return s >= 80 ? '#22c55e' : s >= 50 ? '#f59e0b' : '#ef4444'
})

const scoreOffset = computed(() => {
  const s = report.value?.overallScore ?? 0
  return circumference * (1 - s / 100)
})

const timeline = computed(() => {
  const items = []
  if (!report.value?.skillReports) return items
  for (const sr of report.value.skillReports) {
    if (!sr.executionRecords) continue
    for (const rec of sr.executionRecords) {
      items.push({
        skillName: sr.skillName,
        command: rec.command,
        status: rec.verdict?.status || '?',
        round: rec.round ?? 0
      })
    }
  }
  return items
})

function tlClass(status) {
  return {
    'tl-pass': /^pass$/i.test(status),
    'tl-fail': /^fail$/i.test(status),
    'tl-evolve': /^evolve$/i.test(status),
  }
}

async function loadJson() {
  showJson.value = true
  try {
    const { data } = await getJsonReport(props.taskId)
    jsonText.value = JSON.stringify(data, null, 2)
  } catch {
    jsonText.value = '加载失败'
  }
}

onMounted(async () => {
  try {
    const { data } = await getJsonReport(props.taskId)
    report.value = data
  } catch (e) {
    console.error('加载报告失败', e)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.exec-mode-label {
  display: inline-block; padding: 4px 14px; border-radius: 999px; font-size: 13px;
  font-weight: 500; margin-bottom: 16px;
}
.mode-ssh { background: #eff6ff; color: #1e40af; }
.mode-kubectl { background: #fdf4ff; color: #86198f; }
.mode-offline { background: #fffbeb; color: #92400e; border: 1px solid #fcd34d; }

.score-card { padding: 28px; }
.score-main { display: flex; align-items: center; gap: 40px; }
.score-svg { width: 140px; height: 140px; }
.score-arc { transition: stroke-dashoffset 0.8s ease; }
.score-stats { display: flex; flex-wrap: wrap; gap: 20px; flex: 1; }
.score-stat { display: flex; flex-direction: column; min-width: 100px; }
.stat-label { font-size: 11px; color: #6b7280; text-transform: uppercase; letter-spacing: .05em; }
.stat-value { font-size: 18px; font-weight: 600; }
.skill-card-header { display: flex; justify-content: space-between; align-items: center; padding: 14px 18px; background: #fafafa; margin: -24px -24px 0 -24px; border-radius: 12px 12px 0 0; }
.skill-card-body { padding-top: 16px; }
.context-tag { font-size: 12px; background: #eff6ff; color: #1e40af; padding: 2px 8px; border-radius: 4px; }
.evidence-pre { background: #1e293b; color: #e2e8f0; padding: 10px; border-radius: 6px; font-size: 12px; overflow-x: auto; max-height: 160px; }
.yaml-pre { background: #1e293b; color: #facc15; padding: 10px; border-radius: 6px; font-size: 12px; overflow-x: auto; max-height: 200px; }
.remediation-block { background: #fffbeb; border: 1px solid #fde68a; border-radius: 8px; padding: 14px; margin-bottom: 12px; }
.evolution-badge { background: #f0fdf4; border: 1px solid #86efac; border-radius: 6px; padding: 8px 12px; font-size: 13px; margin-top: 8px; }
.timeline { position: relative; padding-left: 24px; }
.timeline::before { content: ""; position: absolute; left: 8px; top: 0; bottom: 0; width: 2px; background: #e5e7eb; }
.timeline-item { position: relative; margin-bottom: 10px; padding-left: 16px; }
.timeline-dot { position: absolute; left: -18px; top: 8px; width: 14px; height: 14px; border-radius: 50%; border: 2px solid #fff; }
.tl-pass { background: #22c55e; }
.tl-fail { background: #ef4444; }
.tl-evolve { background: #f59e0b; }
.timeline-card { background: #fff; padding: 8px 12px; border-radius: 8px; box-shadow: 0 1px 2px rgba(0,0,0,.05); font-size: 13px; }
.tl-skill { font-size: 11px; color: #6b7280; margin-right: 8px; }
.tl-cmd { font-size: 12px; word-break: break-all; }
.tl-status { color: #6b7280; margin-left: 4px; }
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal-content { background: #fff; border-radius: 12px; padding: 24px; max-width: 800px; width: 90%; max-height: 80vh; overflow: auto; }
.json-pre { background: #1e293b; color: #e2e8f0; padding: 16px; border-radius: 8px; font-size: 12px; overflow: auto; max-height: 60vh; }
</style>
