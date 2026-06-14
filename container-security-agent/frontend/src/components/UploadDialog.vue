<template>
  <div class="dialog-overlay" @click.self="$emit('close')">
    <div class="dialog">
      <div class="dialog-header">
        <h3>[Upload] 上传检测结果</h3>
        <button class="dialog-close" @click="$emit('close')">&times;</button>
      </div>
      <div class="dialog-body">
        <p>任务 ID：<code>{{ taskId }}</code></p>
        <p>请选择由线下脚本生成的 ZIP 结果文件。</p>

        <div class="upload-area">
          <input
            type="file"
            accept=".zip"
            ref="fileInput"
            @change="onFileSelected"
            :disabled="uploading"
          />
        </div>

        <div v-if="selectedFile" class="file-info">
          已选择：<strong>{{ selectedFile.name }}</strong>
          ({{ formatSize(selectedFile.size) }})
        </div>

        <div v-if="progress > 0" class="progress-bar">
          <div class="progress-fill" :style="{ width: progress + '%' }"></div>
          <span class="progress-text">{{ progress }}%</span>
        </div>

        <div v-if="error" class="upload-error">{{ error }}</div>
      </div>
      <div class="dialog-footer">
        <button class="btn btn-secondary" @click="$emit('close')" :disabled="uploading">取消</button>
        <button class="btn btn-primary"
          :disabled="!selectedFile || uploading"
          @click="doUpload">
          {{ uploading ? '上传中...' : '确认上传' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  taskId: String,
  progress: Number,
  error: String,
  uploading: Boolean
})

const emit = defineEmits(['upload', 'close'])

const fileInput = ref(null)
const selectedFile = ref(null)

function onFileSelected(e) {
  const files = e.target.files
  if (files && files.length > 0) {
    selectedFile.value = files[0]
  }
}

function doUpload() {
  if (!selectedFile.value) return
  emit('upload', selectedFile.value)
}

function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}
</script>

<style scoped>
.dialog-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,.5); display: flex;
  align-items: center; justify-content: center; z-index: 1000;
}
.dialog {
  background: #fff; border-radius: 12px; width: 480px; max-width: 90vw;
  box-shadow: 0 8px 30px rgba(0,0,0,.2); overflow: hidden;
}
.dialog-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 20px; border-bottom: 1px solid #e5e7eb;
}
.dialog-header h3 { margin: 0; font-size: 16px; }
.dialog-close {
  border: none; background: none; font-size: 22px; cursor: pointer; color: #9ca3af;
  line-height: 1; padding: 0;
}
.dialog-body { padding: 20px; }
.dialog-body p { font-size: 14px; color: #4b5563; margin: 0 0 12px; line-height: 1.5; }
.dialog-body code { font-size: 13px; background: #f3f4f6; padding: 2px 6px; border-radius: 4px; }
.upload-area { margin: 12px 0; }
.file-info { font-size: 13px; color: #1f2937; margin: 8px 0; background: #f0fdf4; padding: 8px 12px; border-radius: 6px; }
.progress-bar {
  margin-top: 12px; height: 24px; background: #e5e7eb; border-radius: 12px;
  position: relative; overflow: hidden;
}
.progress-fill {
  height: 100%; background: #2563eb; border-radius: 12px; transition: width .3s;
}
.progress-text {
  position: absolute; top: 2px; left: 50%; transform: translateX(-50%);
  font-size: 12px; color: #fff; font-weight: 600;
}
.upload-error { margin-top: 10px; background: #fef2f2; border: 1px solid #fecaca; color: #991b1b; padding: 10px 14px; border-radius: 6px; font-size: 13px; }
.dialog-footer {
  display: flex; justify-content: flex-end; gap: 8px;
  padding: 16px 20px; border-top: 1px solid #e5e7eb;
}
</style>
