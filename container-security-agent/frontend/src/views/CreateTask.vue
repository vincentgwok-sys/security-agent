<template>
  <div>
    <h2 style="margin-bottom: 20px;">创建检测任务</h2>
    <div class="card" style="max-width: 800px;">
      <form @submit.prevent="handleSubmit">

        <!-- Connection Type Selector -->
        <div class="form-group">
          <label>连接方式</label>
          <div class="connection-type-row">
            <label class="conn-type-btn" :class="{ active: form.connectionType === 'ssh' }">
              <input type="radio" value="ssh" v-model="form.connectionType" />
              🔗 直连 SSH
            </label>
            <label class="conn-type-btn" :class="{ active: form.connectionType === 'kubectl' }">
              <input type="radio" value="kubectl" v-model="form.connectionType" />
              ⎈ Kubectl 跳板机
            </label>
          </div>
        </div>

        <!-- Target / Jumpbox fields -->
        <div class="form-group">
          <label>{{ form.connectionType === 'kubectl' ? '跳板机 IP *' : '目标容器 IP *' }}</label>
          <input v-model="form.targetIp" :placeholder="form.connectionType === 'kubectl' ? '跳板机地址，例如：10.0.0.100' : '例如：10.0.0.50'" required />
        </div>

        <div class="form-row">
          <div class="form-group" style="flex: 1;">
            <label>SSH 端口</label>
            <input v-model.number="form.sshPort" type="number" placeholder="22" />
          </div>
          <div class="form-group" style="flex: 2;">
            <label>SSH 用户名 *</label>
            <input v-model="form.sshUser" placeholder="root" required />
          </div>
        </div>

        <div class="form-group">
          <label>SSH 密码 *</label>
          <input v-model="form.sshPassword" type="password" placeholder="输入密码" required />
        </div>

        <!-- Kubectl-specific: namespace + pod browser -->
        <template v-if="form.connectionType === 'kubectl'">
          <div class="form-group">
            <label>Namespace（可选，留空查询所有命名空间）</label>
            <input v-model="kubectlNamespace" placeholder="例：default" />
          </div>

          <div class="form-group">
            <button type="button" class="btn" style="background: #2563eb; color: #fff;"
              :disabled="podLoading || !form.targetIp || !form.sshUser || !form.sshPassword"
              @click="fetchPods">
              {{ podLoading ? '连接中...' : '🔌 连接跳板机获取 Pod 列表' }}
            </button>
          </div>

          <div v-if="podError" class="error-msg">{{ podError }}</div>

          <div v-if="pods.length > 0" class="form-group">
            <label>选择目标 Pod *（共 {{ pods.length }} 个）</label>
            <div class="pod-list">
              <label v-for="pod in pods" :key="pod.name + '/' + pod.namespace"
                class="pod-item" :class="{ selected: form.targetPod === pod.name && form.targetNamespace === pod.namespace }">
                <input type="radio" :value="pod.name" v-model="form.targetPod"
                  @change="form.targetNamespace = pod.namespace" />
                <span class="pod-info">
                  <span class="pod-name">{{ pod.name }}</span>
                  <span class="pod-meta">
                    <code>{{ pod.namespace }}</code>
                    <StatusBadge :status="pod.status" />
                    <small v-if="pod.containers?.length">{{ pod.containers.join(', ') }}</small>
                    <small v-if="pod.nodeName" style="color: #9ca3af;">节点: {{ pod.nodeName }}</small>
                  </span>
                </span>
              </label>
            </div>
          </div>

          <div v-if="form.targetPod" class="success-msg" style="margin-bottom: 0;">
            ✅ 已选择目标 Pod：<strong>{{ form.targetNamespace }}/{{ form.targetPod }}</strong>
          </div>
        </template>

        <!-- Skill Selection -->
        <div class="form-group">
          <label>选择检测 Skill *（可多选）</label>
          <div v-if="skillsLoading" class="loading">加载 Skills...</div>
          <div v-else-if="skills.length === 0" class="empty-state">
            <p>暂无可用 Skill，请在 skills/ 目录下添加 Skill JSON 文件</p>
          </div>
          <div v-else class="skill-checkboxes">
            <label v-for="skill in skills" :key="skill.skillId" class="skill-checkbox">
              <div class="skill-checkbox-top">
                <input type="checkbox" :value="skill.skillId" v-model="form.skillIds" />
                <span class="skill-name">{{ skill.skillName }}</span>
                <RiskLevelTag :level="skill.riskLevel" />
                <span class="skill-meta">{{ skill.skillId }} · {{ skill.contextCount }} 个 Context</span>
              </div>
              <p class="skill-desc-text">{{ skill.description }}</p>
            </label>
          </div>
        </div>

        <div v-if="error" class="error-msg">{{ error }}</div>

        <div style="display: flex; gap: 12px; margin-top: 20px;">
          <button type="submit" class="btn btn-primary"
            :disabled="submitting || form.skillIds.length === 0 || (form.connectionType === 'kubectl' && !form.targetPod)">
            {{ submitting ? '提交中...' : '启动检测' }}
          </button>
          <button type="button" class="btn btn-secondary" @click="resetForm">重置</button>
        </div>
      </form>

      <div v-if="createdTaskId" class="success-msg" style="margin-top: 16px;">
        ✅ 任务已创建！任务 ID：<strong>{{ createdTaskId }}</strong>
        <br />
        <router-link :to="`/tasks`">查看任务列表</router-link>
        &nbsp;|&nbsp;
        <router-link :to="`/report/${createdTaskId}`">查看报告</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { listSkills, createTask, listKubectlPods } from '../api'
import RiskLevelTag from '../components/RiskLevelTag.vue'
import StatusBadge from '../components/StatusBadge.vue'

const skills = ref([])
const skillsLoading = ref(true)
const submitting = ref(false)
const error = ref('')
const createdTaskId = ref('')

const form = reactive({
  connectionType: 'ssh',
  targetIp: '',
  sshPort: 22,
  sshUser: 'root',
  sshPassword: '',
  skillIds: [],
  targetPod: '',
  targetNamespace: ''
})

const kubectlNamespace = ref('')
const pods = ref([])
const podLoading = ref(false)
const podError = ref('')

onMounted(async () => {
  try {
    const { data } = await listSkills()
    skills.value = data
    form.skillIds = data.map(s => s.skillId)
  } catch (e) {
    error.value = '加载 Skill 列表失败: ' + e.message
  } finally {
    skillsLoading.value = false
  }
})

async function fetchPods() {
  podError.value = ''
  podLoading.value = true
  try {
    const { data } = await listKubectlPods({
      jumpboxIp: form.targetIp,
      jumpboxPort: form.sshPort || 22,
      jumpboxUser: form.sshUser,
      jumpboxPassword: form.sshPassword,
      namespace: kubectlNamespace.value || null
    })
    pods.value = data.pods || []
    if (pods.value.length === 0) {
      podError.value = '未找到任何 Pod，请检查 namespace 或跳板机配置'
    }
  } catch (e) {
    podError.value = e.response?.data?.error || e.message || '连接失败'
    pods.value = []
  } finally {
    podLoading.value = false
  }
}

async function handleSubmit() {
  if (form.skillIds.length === 0) {
    error.value = '请至少选择一个 Skill'
    return
  }
  if (form.connectionType === 'kubectl' && !form.targetPod) {
    error.value = '请先连接跳板机并选择一个目标 Pod'
    return
  }
  error.value = ''
  submitting.value = true
  try {
    const { data } = await createTask({
      targetIp: form.targetIp,
      sshPort: form.sshPort,
      sshUser: form.sshUser,
      sshPassword: form.sshPassword,
      skillIds: form.skillIds,
      connectionType: form.connectionType,
      targetPod: form.connectionType === 'kubectl' ? form.targetPod : null,
      targetNamespace: form.connectionType === 'kubectl' ? form.targetNamespace : null
    })
    createdTaskId.value = data.taskId
  } catch (e) {
    error.value = e.response?.data?.error || e.message || '创建任务失败'
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  form.targetIp = ''
  form.sshPort = 22
  form.sshUser = 'root'
  form.sshPassword = ''
  form.skillIds = skills.value.map(s => s.skillId)
  form.connectionType = 'ssh'
  form.targetPod = ''
  form.targetNamespace = ''
  createdTaskId.value = ''
  error.value = ''
  kubectlNamespace.value = ''
  pods.value = []
  podError.value = ''
}
</script>

<style scoped>
.form-row { display: flex; gap: 12px; }

/* Connection type selector */
.connection-type-row { display: flex; gap: 12px; }
.conn-type-btn {
  display: flex; align-items: center; gap: 6px;
  padding: 10px 18px; border: 2px solid #e5e7eb; border-radius: 8px;
  cursor: pointer; transition: all .2s; font-size: 14px; font-weight: 500;
}
.conn-type-btn input { display: none; }
.conn-type-btn:hover { border-color: #93c5fd; background: #eff6ff; }
.conn-type-btn.active { border-color: #2563eb; background: #eff6ff; color: #2563eb; }

/* Skill checkboxes */
.skill-checkboxes { display: flex; flex-direction: column; gap: 10px; max-height: 320px; overflow-y: auto; }
.skill-checkbox { display: flex; flex-direction: column; gap: 8px; padding: 14px 16px; border: 1px solid #e5e7eb; border-radius: 8px; cursor: pointer; transition: all .2s; }
.skill-checkbox:hover { border-color: #93c5fd; background: #eff6ff; }
.skill-checkbox-top { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.skill-checkbox-top input { flex-shrink: 0; margin: 0; }
.skill-name { font-size: 14px; font-weight: 600; }
.skill-meta { font-size: 12px; color: #9ca3af; }
.skill-desc-text { font-size: 13px; color: #6b7280; line-height: 1.5; margin: 0; padding-left: 23px; }

/* Pod list */
.pod-list { display: flex; flex-direction: column; gap: 6px; max-height: 300px; overflow-y: auto; }
.pod-item {
  display: flex; align-items: flex-start; gap: 10px;
  padding: 10px 14px; border: 1px solid #e5e7eb; border-radius: 8px;
  cursor: pointer; transition: all .2s;
}
.pod-item:hover { border-color: #93c5fd; background: #f8fafc; }
.pod-item.selected { border-color: #2563eb; background: #eff6ff; }
.pod-item input { margin-top: 3px; flex-shrink: 0; }
.pod-info { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.pod-name { font-size: 14px; font-weight: 600; }
.pod-meta { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; font-size: 12px; color: #6b7280; }
.pod-meta code { font-size: 11px; background: #f3f4f6; padding: 1px 5px; border-radius: 3px; }

.success-msg { background: #f0fdf4; border: 1px solid #86efac; color: #166534; padding: 12px 16px; border-radius: 8px; font-size: 14px; }
.success-msg a { color: #1d4ed8; }
.error-msg { background: #fef2f2; border: 1px solid #fecaca; color: #991b1b; padding: 10px 14px; border-radius: 6px; font-size: 13px; margin-top: 8px; }
</style>
