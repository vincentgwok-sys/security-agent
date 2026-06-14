<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;">
      <h2>任务列表</h2>
      <router-link to="/create" class="btn btn-primary">+ 创建任务</router-link>
    </div>

    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="tasks.length === 0" class="empty-state">
      <div class="icon">[List]</div>
      <p>暂无检测任务，请创建一个新任务</p>
    </div>
    <div v-else class="card" style="padding: 0; overflow: hidden;">
      <table>
        <thead>
          <tr>
            <th>任务 ID</th>
            <th>连接方式</th>
            <th>目标</th>
            <th>状态</th>
            <th>Skill 数</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in tasks" :key="task.taskId">
            <td><code>{{ task.taskId }}</code></td>
            <td><span class="conn-icon">{{ connIcon(task.connectionType) }}</span></td>
            <td>{{ connTarget(task) }}</td>
            <td><StatusBadge :status="task.status" :connectionType="task.connectionType" /></td>
            <td>{{ task.skillIds?.length || 0 }}</td>
            <td>{{ formatTime(task.createdAt) }}</td>
            <td>
              <div style="display: flex; gap: 6px;">
                <!-- Completed: view report -->
                <router-link :to="`/report/${task.taskId}`" class="btn btn-sm btn-success" v-if="task.status === 'COMPLETED'">
                  查看报告
                </router-link>
                <!-- Online running/created -->
                <span v-else-if="task.status === 'RUNNING' && task.connectionType !== 'offline'" class="btn btn-sm btn-secondary" style="cursor: default; opacity: .6;">
                  检测中...
                </span>
                <span v-else-if="task.status === 'CREATED' && task.connectionType !== 'offline'" class="btn btn-sm btn-secondary" style="cursor: default; opacity: .6;">
                  等待中
                </span>
                <!-- Offline: upload button -->
                <button class="btn btn-sm" style="background: #2563eb; color: #fff;"
                  v-if="task.connectionType === 'offline' && (task.status === 'CREATED' || task.status === 'SCRIPT_DOWNLOADED')"
                  @click="showUploadDialog(task.taskId)">
                  上传结果
                </button>
                <!-- Offline analyzing -->
                <span v-else-if="task.status === 'ANALYZING'" class="btn btn-sm btn-secondary" style="cursor: default; opacity: .6;">
                  分析中...
                </span>
                <!-- Logs -->
                <router-link :to="`/logs/${task.taskId}`" class="btn btn-sm"
                  style="background: #6366f1; color: #fff;" v-if="task.status !== 'CREATED'">
                  查看日志
                </router-link>
                <!-- Cancel (online only) -->
                <button class="btn btn-sm" style="background: #dc2626; color: #fff;"
                  v-if="(task.status === 'RUNNING' || task.status === 'CREATED') && task.connectionType !== 'offline'"
                  :disabled="cancelling === task.taskId"
                  @click="cancelTask(task)">
                  {{ cancelling === task.taskId ? '终止中...' : '终止' }}
                </button>
                <!-- Retry (online only) -->
                <button class="btn btn-sm" style="background: #f59e0b; color: #fff;"
                  v-if="(task.status === 'INTERRUPTED' || task.status === 'FAILED') && task.connectionType !== 'offline' && !isRetried(task.taskId)"
                  :disabled="retrying === task.taskId"
                  @click="retryTask(task)">
                  {{ retrying === task.taskId ? '重试中...' : '重试' }}
                </button>
                <!-- Delete -->
                <button class="btn btn-sm btn-secondary"
                  v-if="task.status === 'COMPLETED' || task.status === 'INTERRUPTED' || task.status === 'FAILED'"
                  :disabled="deleting === task.taskId"
                  @click="removeTask(task)">
                  {{ deleting === task.taskId ? '删除中...' : '删除' }}
                </button>
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

    <div style="display: flex; justify-content: flex-end; align-items: center; gap: 10px; margin-top: 12px;">
      <span v-if="autoRefresh && hasActiveTasks" style="font-size: 12px; color: #16a34a;">
        Auto 刷新中 ({{ REFRESH_INTERVAL / 1000 }}s)
      </span>
      <button class="btn btn-sm" :style="{ background: autoRefresh ? '#16a34a' : '#6b7280', color: '#fff' }"
        @click="toggleAutoRefresh">
        {{ autoRefresh ? 'Auto: ON' : 'Auto: OFF' }}
      </button>
      <button class="btn btn-sm btn-secondary" @click="refresh" :disabled="loading">刷新</button>
    </div>

    <!-- Upload dialog -->
    <UploadDialog
      v-if="showUpload"
      :taskId="uploadTaskId"
      :progress="uploadProgress"
      :error="uploadError"
      :uploading="uploading"
      @upload="handleUpload"
      @close="showUpload = false"
    />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import { listTasks, getTask, createTask, cancelTask as apiCancelTask,
         deleteTask as apiDeleteTask, uploadResults } from '../api'
import StatusBadge from '../components/StatusBadge.vue'
import UploadDialog from '../components/UploadDialog.vue'

const tasks = ref([])
const loading = ref(true)
const page = ref(0)
const totalPages = ref(0)
const retrying = ref(null)
const cancelling = ref(null)
const deleting = ref(null)
const autoRefresh = ref(true)
let refreshTimer = null
const REFRESH_INTERVAL = 5000 // 5 秒轮询

const hasActiveTasks = computed(() =>
  tasks.value.some(t => t.status === 'RUNNING' || t.status === 'CREATED' || t.status === 'ANALYZING')
)

// Upload dialog state
const uploadTaskId = ref(null)
const showUpload = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const uploadError = ref('')

function connIcon(type) {
  if (type === 'kubectl') return 'k8s'
  if (type === 'offline') return '[DL]'
  return '[SSH]'
}

function connTarget(task) {
  if (task.connectionType === 'offline') return '线下执行'
  return (task.targetIp || '?') + ':' + (task.sshPort || 22)
}

function showUploadDialog(taskId) {
  uploadTaskId.value = taskId
  uploadError.value = ''
  uploadProgress.value = 0
  showUpload.value = true
}

async function handleUpload(file) {
  uploading.value = true
  uploadError.value = ''
  uploadProgress.value = 0
  try {
    await uploadResults(uploadTaskId.value, file, (e) => {
      uploadProgress.value = Math.round(e.loaded / e.total * 100)
    })
    showUpload.value = false
    fetchTasks()
  } catch (e) {
    uploadError.value = e.response?.data?.error || e.message || '上传失败'
  } finally {
    uploading.value = false
  }
}

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

function toggleAutoRefresh() {
  autoRefresh.value = !autoRefresh.value
  if (autoRefresh.value) {
    startAutoRefresh()
  } else {
    stopAutoRefresh()
  }
}

function startAutoRefresh() {
  stopAutoRefresh()
  refreshTimer = setInterval(() => {
    if (hasActiveTasks.value) {
      fetchTasks()
    }
  }, REFRESH_INTERVAL)
}

function stopAutoRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

onMounted(() => {
  fetchTasks()
  startAutoRefresh()
})

onUnmounted(stopAutoRefresh)

async function retryTask(task) {
  if (!confirm(`确定要重试任务 ${task.taskId} 吗？将使用最新的 Skill 和规则重新检测。`)) return
  retrying.value = task.taskId
  try {
    const { data: oldTask } = await getTask(task.taskId)
    await createTask({
      targetIp: oldTask.targetIp,
      sshUser: oldTask.sshUser,
      sshPassword: oldTask.sshPassword,
      sshPort: oldTask.sshPort || 22,
      skillIds: oldTask.skillIds,
      parentTaskId: oldTask.taskId
    })
    fetchTasks()
  } catch (e) {
    console.error('重试任务失败', e)
    alert('重试失败: ' + (e.response?.data?.error || e.message))
  } finally {
    retrying.value = null
  }
}

function isRetried(taskId) {
  return tasks.value.some(t => t.parentTaskId === taskId)
}

async function cancelTask(task) {
  if (!confirm(`确定要终止任务 ${task.taskId} 吗？`)) return
  cancelling.value = task.taskId
  try {
    await apiCancelTask(task.taskId)
    fetchTasks()
  } catch (e) {
    console.error('终止任务失败', e)
    alert('终止失败: ' + (e.response?.data?.error || e.message))
  } finally {
    cancelling.value = null
  }
}

async function removeTask(task) {
  if (!confirm(`确定要删除任务 ${task.taskId} 吗？\n（日志和报告将保留）`)) return
  deleting.value = task.taskId
  try {
    await apiDeleteTask(task.taskId)
    fetchTasks()
  } catch (e) {
    console.error('删除任务失败', e)
    alert('删除失败: ' + (e.response?.data?.error || e.message))
  } finally {
    deleting.value = null
  }
}

function formatTime(dateStr) {
  if (!dateStr) return '-'
  try {
    return new Date(dateStr).toLocaleString('zh-CN')
  } catch {
    return dateStr
  }
}

</script>
