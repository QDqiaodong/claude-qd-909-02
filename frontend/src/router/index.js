import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import CanalView from '../views/CanalView.vue'
import GateView from '../views/GateView.vue'
import PlanView from '../views/PlanView.vue'
import OutageView from '../views/OutageView.vue'
import LevelView from '../views/LevelView.vue'
import EmergencyView from '../views/EmergencyView.vue'
import RequestView from '../views/RequestView.vue'

const routes = [
  { path: '/', redirect: '/canal' },
  { path: '/canal', name: 'canal', component: CanalView },
  { path: '/gate', name: 'gate', component: GateView },
  { path: '/plan', name: 'plan', component: PlanView },
  { path: '/outage', name: 'outage', component: OutageView },
  { path: '/level', name: 'level', component: LevelView },
  { path: '/emergency', name: 'emergency', component: EmergencyView },
  { path: '/request', name: 'request', component: RequestView },
  { path: '/:pathMatch(.*)*', redirect: '/canal' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
