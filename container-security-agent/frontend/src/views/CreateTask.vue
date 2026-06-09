<template>
  <div>
    <h2 style="margin-bottom: 20px;">创建检测任务</h2>
    <div class="card" style="max-width: 640px;">
      <form @submit.prevent="handleSubmit">
        <div class="form-group">
          <label>目标容器 IP *</label>
          <input v-model="form.targetIp" placeholder="例如：10.0.0.50" required />
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

        <div class="form-group">
          <label>选择检测 Skill *（可多选）</label>
          <div v-if="skillsLoading" class="loading">加载 Skills...</div>
          <div v-else-if="skills.length === 0" class="empty-state">
            <p>暂无可用 Skill，请在 skills/ 目录下添加 Skill JSON 文件</p>
          </div>
          <div v-else class="skill-checkboxes">
            <label v-for="skill in skills" :key="skill.skillId" class="skill-checkbox">
              <input type="checkbox" :value="skill.skillId" v-model="form.skillIds" />
              <span class="skill-info">
                <strong>{{ skill.skillName }}</strong>
                <small>{{ skill.skillId }} · {{ skill.contextCount }} 个 Context</small>
                <RiskLevelTag :level="skill.riskLevel" />
              </span>
            </label>
          </div>
        </div>

        <div v-if="error" class="error-msg">{{ error }}</div>

        <div style="display: flex; gap: 12px; margin-top: 20px;">
          <button type="submit" class="btn btn-primary" :disabled="submitting || form.skillIds.length === 0">
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
import { listSkills, createTask } from '../api'
import RiskLevelTag from '../components/RiskLevelTag.vue'

const skills = ref([])
const skillsLoading = ref(true)
const submitting = ref(false)
const error = ref('')
const createdTaskId = ref('')

const form = reactive({
  targetIp: '',
  sshPort: 22,
  sshUser: 'root',
  sshPassword: '',
  skillIds: []
})

onMounted(async () => {
  try {
    const { data } = await listSkills()
    skills.value = data
  } catch (e) {
    error.value = '加载 Skill 列表失败: ' + e.message
  } finally {
    skillsLoading.value = false
  }
})

async function handleSubmit() {
  if (form.skillIds.length === 0) {
    error.value = '请至少选择一个 Skill'
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
      skillIds: form.skillIds
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
  form.skillIds = []
  createdTaskId.value = ''
  error.value = ''
}
</script>

<style scoped>
.form-row { display: flex; gap: 12px; }
.skill-checkboxes { display: flex; flex-direction: column; gap: 8px; max-height: 240px; overflow-y: auto; }
.skill-checkbox { display: flex; align-items: flex-start; gap: 8px; padding: 10px 12px; border: 1px solid #e5e7eb; border-radius: 8px; cursor: pointer; transition: all .2s; }
.skill-checkbox:hover { border-color: #93c5fd; background: #eff6ff; }
.skill-checkbox input { margin-top: 3px; }
.skill-info { display: flex; flex-direction: column; gap: 2px; }
.skill-info strong { font-size: 14px; }
.skill-info small { font-size: 12px; color: #6b7280; }
.success-msg { background: #f0fdf4; border: 1px solid #86efac; color: #166534; padding: 12px 16px; border-radius: 8px; font-size: 14px; }
.success-msg a { color: #1d4ed8; }
</style>
