<template>
  <span class="risk-tag" :class="'risk-' + level.toLowerCase()">{{ level }}</span>
</template>

<script setup>
defineProps({ level: { type: String, required: true } })
</script>

<style scoped>
.risk-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}
.risk-critical { background: #fef2f2; color: #991b1b; }
.risk-high { background: #fff7ed; color: #c2410c; }
.risk-low { background: #fefce8; color: #a16207; }
.risk-info { background: #eff6ff; color: #1e40af; }
</style>
