<template>
  <span class="status-badge" :class="'badge-' + normalized">{{ label }}</span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  status: { type: String, required: true },
  connectionType: { type: String, default: null }
})

const offlineLabels = {
  'CREATED': '等待下载',
  'SCRIPT_DOWNLOADED': '等待执行',
  'RESULTS_UPLOADED': '已上传',
  'ANALYZING': '分析中'
}

const label = computed(() => {
  if (props.connectionType === 'offline' && offlineLabels[props.status]) {
    return offlineLabels[props.status]
  }
  if (props.status === 'CREATED') return '等待中'
  if (props.status === 'RUNNING') return '检测中'
  if (props.status === 'INTERRUPTED') return '已中断'
  if (props.status === 'FAILED') return '失败'
  if (props.status === 'COMPLETED') return '已完成'
  return props.status
})

const normalized = computed(() => {
  if (props.status === 'ANALYZING') return 'running'
  if (props.status === 'SCRIPT_DOWNLOADED' || props.status === 'RESULTS_UPLOADED') return 'created'
  return props.status.toLowerCase()
})
</script>

<style scoped>
.status-badge {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}
.badge-pass { background: #dcfce7; color: #166534; }
.badge-fail { background: #fef2f2; color: #991b1b; }
.badge-evolve { background: #fefce8; color: #a16207; }
.badge-env_mismatch { background: #ede9fe; color: #6b21a8; }
.badge-created { background: #eff6ff; color: #1e40af; }
.badge-running { background: #fefce8; color: #a16207; }
.badge-completed { background: #dcfce7; color: #166534; }
.badge-interrupted { background: #f3f4f6; color: #6b7280; }
.badge-pending { background: #fefce8; color: #a16207; }
.badge-succeeded { background: #dcfce7; color: #166534; }
.badge-unknown { background: #f3f4f6; color: #9ca3af; }
</style>
