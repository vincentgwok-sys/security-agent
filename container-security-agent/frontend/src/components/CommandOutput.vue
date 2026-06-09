<template>
  <div class="cmd-output">
    <div class="cmd-output-header" @click="expanded = !expanded">
      <span class="toggle">{{ expanded ? '▼' : '▶' }}</span>
      <code class="cmd-text">$ {{ command }}</code>
      <span class="exit-badge" v-if="exitCode !== undefined && exitCode !== 0">exit: {{ exitCode }}</span>
    </div>
    <div v-if="expanded" class="cmd-output-body">
      <div v-if="stdout" class="output-block stdout-block">
        <div class="output-label">stdout</div>
        <pre>{{ stdout }}</pre>
      </div>
      <div v-if="stderr" class="output-block stderr-block">
        <div class="output-label">stderr</div>
        <pre>{{ stderr }}</pre>
      </div>
      <div v-if="!stdout && !stderr" class="output-empty">(无输出)</div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

defineProps({
  command: { type: String, default: '' },
  stdout: { type: String, default: '' },
  stderr: { type: String, default: '' },
  exitCode: { type: Number, default: 0 }
})

const expanded = ref(false)
</script>

<style scoped>
.cmd-output { border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; margin-bottom: 8px; }
.cmd-output-header { display: flex; align-items: center; gap: 8px; padding: 8px 12px; background: #f9fafb; cursor: pointer; font-size: 13px; }
.cmd-output-header:hover { background: #f3f4f6; }
.toggle { font-size: 10px; color: #9ca3af; flex-shrink: 0; }
.cmd-text { font-size: 13px; color: #374151; word-break: break-all; flex: 1; }
.exit-badge { font-size: 11px; background: #fef2f2; color: #dc2626; padding: 1px 6px; border-radius: 3px; flex-shrink: 0; }
.cmd-output-body { padding: 8px 12px; }
.output-block { margin-bottom: 8px; }
.output-label { font-size: 10px; font-weight: 600; color: #6b7280; text-transform: uppercase; margin-bottom: 2px; }
.output-block pre { font-size: 12px; padding: 8px; border-radius: 4px; max-height: 200px; overflow: auto; white-space: pre-wrap; word-break: break-all; }
.stdout-block pre { background: #f0fdf4; color: #166534; }
.stderr-block pre { background: #fef2f2; color: #991b1b; }
.output-empty { font-size: 12px; color: #9ca3af; font-style: italic; }
</style>
