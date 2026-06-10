import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

// ========== Tasks ==========
export function createTask(data) {
  return api.post('/tasks', data)
}

export function listTasks(page = 0, size = 20) {
  return api.get('/tasks', { params: { page, size } })
}

export function getTask(taskId) {
  return api.get(`/tasks/${taskId}`)
}

// ========== Reports ==========
export function getHtmlReport(taskId) {
  return api.get(`/tasks/${taskId}/report`, { responseType: 'text' })
}

export function getJsonReport(taskId) {
  return api.get(`/tasks/${taskId}/report/json`)
}

// ========== Skills ==========
export function listSkills() {
  return api.get('/skills')
}

export function getSkill(skillId) {
  return api.get(`/skills/${skillId}`)
}

export function getSkillContexts(skillId) {
  return api.get(`/skills/${skillId}/contexts`)
}

export function getSkillContext(skillId, contextId) {
  return api.get(`/skills/${skillId}/contexts/${contextId}`)
}

export function getSkillHistory(skillId) {
  return api.get(`/skills/${skillId}/history`)
}

export function reloadSkills() {
  return api.post('/skills/reload')
}

// ========== Kubectl ==========
export function listKubectlPods(data) {
  return api.post('/kubectl/pods', data)
}

// ========== Task actions ==========
export function cancelTask(taskId) {
  return api.post(`/tasks/${taskId}/cancel`)
}

export function deleteTask(taskId) {
  return api.delete(`/tasks/${taskId}`)
}

// ========== Rules ==========
export function getRules() {
  return api.get('/rules')
}

export function getRuleHistory() {
  return api.get('/rules/history')
}

export function reloadRules() {
  return api.post('/rules/reload')
}

// ========== Logs ==========
export function fetchAiLogs(taskId, skillId, page = 0, size = 50) {
  const params = { taskId, page, size }
  if (skillId) params.skillId = skillId
  return api.get('/logs/ai', { params })
}

export default api
