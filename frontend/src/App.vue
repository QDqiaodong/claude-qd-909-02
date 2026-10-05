<script setup>
import { computed, onMounted, ref } from 'vue'
import router from './router'
import api from './api'

import CanalView from './views/CanalView.vue'
import GateView from './views/GateView.vue'
import PlanView from './views/PlanView.vue'
import OutageView from './views/OutageView.vue'
import LevelView from './views/LevelView.vue'
import EmergencyView from './views/EmergencyView.vue'
import RequestView from './views/RequestView.vue'

// 左侧阶梯梯形块：一块一层，层层往下错开；选中的那块向右突出并加深。
// 页面没有顶部菜单栏，模块切换全靠这一列。
const modules = [
  { key: 'canal', path: '/canal', name: '渠系', desc: '干渠 / 支渠 / 斗渠三级' },
  { key: 'gate', path: '/gate', name: '闸门', desc: '开度滑杆 · 安全区色带' },
  { key: 'plan', path: '/plan', name: '灌溉计划', desc: '周期甘特 · 拖动改天数' },
  { key: 'outage', path: '/outage', name: '停水窗口', desc: '检修停水 · 待避让处理' },
  { key: 'level', path: '/level', name: '水位量测', desc: '折线 · 值对象派生列' },
  { key: 'emergency', path: '/emergency', name: '险情处置', desc: '异常水位 · 接管互斥 · 状态链' },
  { key: 'request', path: '/request', name: '用水申请', desc: '待审 → 已批 → 已供水' }
]

// main.js 里没有 app.use(router)，这里自己驱动路由实例并用 <component :is> 渲染
const viewMap = {
  '/canal': CanalView,
  '/gate': GateView,
  '/plan': PlanView,
  '/outage': OutageView,
  '/level': LevelView,
  '/emergency': EmergencyView,
  '/request': RequestView
}

const currentPath = computed(() => router.currentRoute.value?.path || '/canal')
const currentView = computed(() => viewMap[currentPath.value] || CanalView)

const overview = ref(null)

function go(path) {
  router.push({ path })
}

async function loadOverview() {
  try {
    overview.value = await api.stats.overview()
  } catch (e) {
    overview.value = null
  }
}

onMounted(() => {
  loadOverview()
  // 路由没有 install，初始 currentRoute 是 START_LOCATION，这里按地址栏补一次导航
  const hash = window.location.hash.replace(/^#/, '')
  router.replace(hash.startsWith('/') && hash !== '/' ? hash : '/canal')
})
</script>

<template>
  <div class="app-shell">
    <header class="app-brand">
      <div class="brand-mark"><span>渠</span></div>
      <div>
        <div class="brand-title">水库灌区 · 渠系闸门与灌溉调度管理</div>
        <div class="brand-sub">渠系 · 闸门 · 灌溉计划 · 水位量测 · 用水申请</div>
      </div>
      <div class="top-stats" v-if="overview">
        <div class="top-stat"><b>{{ overview.canalCount }}</b><span>渠道</span></div>
        <div class="top-stat"><b>{{ overview.segmentCount }}</b><span>渠段</span></div>
        <div class="top-stat"><b>{{ overview.gateCount }}</b><span>闸门</span></div>
        <div class="top-stat"><b>{{ overview.pendingOperationCount }}</b><span>待审批操作</span></div>
        <div class="top-stat"><b>{{ overview.planCount }}</b><span>灌溉计划</span></div>
        <div class="top-stat"><b>{{ overview.pendingRequestCount }}</b><span>待审申请</span></div>
      </div>
    </header>

    <div class="app-body">
      <!-- 左侧阶梯梯形块导航：唯一导航 -->
      <nav class="stair-nav">
        <div class="stair-caption">IRRIGATION MODULES</div>
        <div
          v-for="(mod, idx) in modules"
          :key="mod.key"
          class="stair-item"
          :class="{ on: currentPath === mod.path }"
          :style="{ '--i': idx }"
          @click="go(mod.path)"
        >
          <span class="stair-index">{{ idx + 1 }}</span>
          <span class="stair-text">
            <span class="stair-name">{{ mod.name }}</span>
            <span class="stair-desc">{{ mod.desc }}</span>
          </span>
        </div>
      </nav>

      <main class="app-content">
        <component :is="currentView" :key="currentPath" />
      </main>
    </div>
  </div>
</template>
