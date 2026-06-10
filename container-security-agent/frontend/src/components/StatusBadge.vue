<template>
  <span class="status-badge" :class="'badge-' + status.toLowerCase()">{{ status }}</span>
</template>

<script setup>
defineProps({ status: { type: String, required: true } })
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
