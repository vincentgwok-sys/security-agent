import { createRouter, createWebHistory } from 'vue-router'
import TaskList from '../views/TaskList.vue'
import CreateTask from '../views/CreateTask.vue'
import ReportView from '../views/ReportView.vue'
import SkillView from '../views/SkillView.vue'
import AiLogViewer from '../views/AiLogViewer.vue'

const routes = [
  { path: '/', redirect: '/tasks' },
  { path: '/tasks', name: 'TaskList', component: TaskList },
  { path: '/create', name: 'CreateTask', component: CreateTask },
  { path: '/report/:taskId', name: 'ReportView', component: ReportView, props: true },
  { path: '/skills', name: 'SkillView', component: SkillView },
  { path: '/logs/:taskId', name: 'AiLogViewer', component: AiLogViewer, props: true }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
