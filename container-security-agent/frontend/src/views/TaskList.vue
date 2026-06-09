<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
      <h2>任务列表</h2>
      <router-link to="/create" class="btn btn-primary">+ 创建任务</router-link>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="tasks.length === 0" class="empty-state">
      <div class="icon">📋</div>
      <p>暂无检测任务，请创建一个新任务</p>
    </div>
    <div v-else class="card" style="padding: 0; overflow: hidden;">
      <table>
        <thead>
          <tr>
            <th>任务 ID</th>
            <th>目标 IP</th>
            <th>状态</th>
            <th>Skill 数</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in tasks" :key="task.taskId">
            <td><code>{{ task.taskId }}</code></td>
            <td>{{ task.targetIp }}:{{ task.sshPort || 22 }}</td>
            <td><StatusBadge :status="task.status" /></td>
            <td>{{ task.skillIds?.length || 0 }}</td>
            <td>{{ formatTime(task.createdAt) }}</td>
            <td>
              <div style="display: flex; gap: 6px;">
                <router-link :to="`/report/${task.taskId}`" class="btn btn-sm btn-success" v-if="task.status === 'COMPLETED'">
                  查看报告
                </router-link>
                <span v-else class="btn btn-sm btn-secondary" style="cursor: default; opacity: .6;">
                  {{ task.status === 'RUNNING' ? '检测中...' : '等待中' }}
                </span>
                <router-link :to="`/logs/${task.taskId}`" class="btn btn-sm"
                  style="background: #6366f1; color: #fff;" v-if="task.status !== 'CREATED'">
                  查看日志
                </router-link>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pagination" v-if="totalPages > 1">
      <button :disabled="page <= 0" @click="page--">上一页</button>
      <span>{{ page + 1 }} / {{ totalPages }}</span>
      <button :disabled="page >= totalPages - 1" @click="page++">下一页</button>
    </div>

    <div style="display: flex; justify-content: flex-end; margin-top: 12px;">
      <button class="btn btn-sm btn-secondary" @click="refresh" :disabled="loading">刷新</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { listTasks } from '../api'
import StatusBadge from '../components/StatusBadge.vue'

const tasks = ref([])
const loading = ref(true)
const page = ref(0)
const totalPages = ref(0)

async function fetchTasks() {
  loading.value = true
  try {
    const { data } = await listTasks(page.value, 20)
    tasks.value = data.content || []
    totalPages.value = data.totalPages || 0
  } catch (e) {
    console.error('获取任务列表失败', e)
  } finally {
    loading.value = false
  }
}

function refresh() {
  page.value = 0
  fetchTasks()
}

function formatTime(dateStr) {
  if (!dateStr) return '-'
  try {
    return new Date(dateStr).toLocaleString('zh-CN')
  } catch {
    return dateStr
  }
}

onMounted(fetchTasks)
</script>
