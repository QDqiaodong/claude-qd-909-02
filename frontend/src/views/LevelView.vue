<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import router from '../router'

const series = ref([])
const levels = ref([])
const derived = ref([])
const loading = ref(false)
const activeId = ref(null)
const stationFilter = ref('ALL')

const stations = computed(() => series.value || [])

const visibleStations = computed(() =>
  stationFilter.value === 'ALL'
    ? stations.value
    : stations.value.filter((s) => s.stationCode === stationFilter.value)
)

const activePoint = computed(() => {
  for (const s of stations.value) {
    const p = (s.points || []).find((x) => x.id === activeId.value)
    if (p) return { station: s, point: p }
  }
  return null
})

const CHART_H = 210
const PAD_Y = 22

function chartWidth(s) {
  const n = (s.points || []).length
  return Math.max(560, 90 + n * 84)
}

function scaleOf(s) {
  const pts = s.points || []
  const values = pts.map((p) => Number(p.value || 0))
    .concat([Number(s.designLowLevel || 0), Number(s.designHighLevel || 0)])
    .filter((v) => v > 0)
  const lo = Math.min(...values) - 0.3
  const hi = Math.max(...values) + 0.3
  return { lo, hi: hi === lo ? lo + 1 : hi }
}

function yOf(s, v) {
  const { lo, hi } = scaleOf(s)
  const ratio = (hi - Number(v || 0)) / (hi - lo)
  return PAD_Y + ratio * (CHART_H - PAD_Y * 2)
}

function xOf(s, i) {
  return 62 + i * 84
}

function linePoints(s) {
  return (s.points || []).map((p, i) => `${xOf(s, i)},${yOf(s, p.value)}`).join(' ')
}

function bandRect(s) {
  const top = yOf(s, s.designHighLevel)
  const bottom = yOf(s, s.designLowLevel)
  const width = chartWidth(s) - 70
  return { x: 40, y: top, width, height: Math.max(2, bottom - top) }
}

function pointColor(p) {
  if (p.abnormal === 1) return '#e53935'
  if (p.safetyGrade === 'SURGE') return '#f9a825'
  return '#0288d1'
}

function selectPoint(p) {
  activeId.value = activeId.value === p.id ? null : p.id
}

async function refresh(row) {
  loading.value = true
  try {
    await api.level.refresh(row.id)
    ElMessage.success('派生值已重算')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function createEmergency(row) {
  loading.value = true
  try {
    const d = await api.emergency.create({ waterLevelId: row.id, creator: '值班员' })
    ElMessage.success('险情处置单已发起，等待接管')
    router.push({ path: '/emergency', query: { levelId: row.id, orderId: d.order.id } })
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function openEmergency(id) {
  router.push({ path: '/emergency', query: { orderId: id } })
}

async function load() {
  loading.value = true
  try {
    const [s, v, d] = await Promise.all([api.level.series(), api.level.vo(), api.level.derived()])
    series.value = s || []
    levels.value = v || []
    derived.value = d || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">水位量测 · 折线与值对象派生列</span>
      <span class="view-tip">带 ƒ 标记的列不是表字段，是值对象读库时派生出来的</span>
    </div>

    <div class="panel">
      <div class="panel-title">派生列清单（来自 /api/level/derived）</div>
      <div v-if="!derived.length" class="empty-tip" style="padding:10px">后端未返回派生列说明</div>
      <div class="derived-cols">
        <div v-for="d in derived" :key="d.field" class="derived-col">
          <div class="dc-head">
            <span class="derived">{{ d.label }}</span>
            <code>{{ d.field }}</code>
          </div>
          <div class="dc-owner">值对象：{{ d.owner }}</div>
          <div class="dc-owner">实例化器：{{ d.instantiator }}</div>
        </div>
      </div>
    </div>

    <div class="chip-row">
      <span class="chip" :class="{ on: stationFilter === 'ALL' }" @click="stationFilter = 'ALL'">全部测站</span>
      <span
        v-for="s in stations"
        :key="s.stationCode"
        class="chip"
        :class="{ on: stationFilter === s.stationCode }"
        @click="stationFilter = s.stationCode"
      >{{ s.stationCode }}（{{ s.segmentCode }}）</span>
    </div>

    <div class="panel">
      <div class="panel-title">水位折线（含设计水位区间带）</div>
      <div v-if="!visibleStations.length" class="empty-tip">暂无量测数据</div>
      <div v-for="s in visibleStations" :key="s.stationCode" class="chart-block">
        <div class="chart-head">
          <b>{{ s.stationCode }}</b>
          <span class="op-meta">{{ s.segmentName }}（{{ s.segmentCode }}）</span>
          <span class="tag-line">设计水位 {{ s.designLowLevel }} ~ {{ s.designHighLevel }} m</span>
          <span v-if="s.abnormalCount" class="tag-line" style="color:#e53935">
            <i class="dot" style="background:#e53935"></i>{{ s.abnormalCount }} 个异常点
          </span>
        </div>
        <svg class="chart-svg" :viewBox="`0 0 ${chartWidth(s)} ${CHART_H}`">
          <!-- 设计水位区间带 -->
          <rect
            :x="bandRect(s).x" :y="bandRect(s).y"
            :width="bandRect(s).width" :height="bandRect(s).height"
            fill="#e1f5fe" stroke="#4fc3f7" stroke-dasharray="5 4" rx="6"
          />
          <text :x="46" :y="bandRect(s).y - 4" font-size="10" fill="#0277bd">设计高水位 {{ s.designHighLevel }}</text>
          <text :x="46" :y="bandRect(s).y + bandRect(s).height + 12" font-size="10" fill="#0277bd">设计低水位 {{ s.designLowLevel }}</text>

          <polyline :points="linePoints(s)" fill="none" stroke="#0288d1" stroke-width="2.2" />

          <template v-for="(p, i) in s.points" :key="p.id">
            <circle
              :cx="xOf(s, i)" :cy="yOf(s, p.value)" :r="activeId === p.id ? 7 : 5"
              :fill="pointColor(p)" stroke="#fff" stroke-width="2"
              class="pt" @click="selectPoint(p)"
            />
            <text :x="xOf(s, i)" :y="yOf(s, p.value) - 12" text-anchor="middle" font-size="10" fill="#10304a">
              {{ p.value }}
            </text>
            <text :x="xOf(s, i)" :y="CHART_H - 4" text-anchor="middle" font-size="9.5" fill="#8aa6b8">
              {{ (p.label || '').slice(5, 16) }}
            </text>
          </template>
        </svg>

        <div v-if="activePoint && activePoint.station.stationCode === s.stationCode" class="pt-detail">
          <b>{{ activePoint.point.label }}</b>
          <span>实测 {{ activePoint.point.value }} m</span>
          <span class="derived" title="MeasureWindow 派生">时长 {{ activePoint.point.durationMinutes }} 分</span>
          <span class="derived" title="LevelBand 派生">变幅 {{ activePoint.point.bandSpan }} m</span>
          <span class="derived" title="LevelBand 派生">安全等级 {{ activePoint.point.safetyGrade }}</span>
          <span v-if="activePoint.point.abnormal === 1" style="color:#e53935;font-weight:700">
            异常：{{ activePoint.point.abnormalReason }}
          </span>
          <el-button
            v-if="activePoint.point.abnormal === 1 && activePoint.point.emergencyOpen"
            size="small" type="warning" plain
            @click="openEmergency(activePoint.point.emergencyOrderId)"
          >查看险情处置单</el-button>
          <el-button
            v-else-if="activePoint.point.abnormal === 1"
            size="small" type="danger" plain :loading="loading"
            @click="createEmergency(activePoint.point)"
          >发起险情处置</el-button>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-title">量测明细 · 基础列与派生列分栏</div>
      <div v-if="!levels.length" class="empty-tip">暂无量测记录</div>
      <div v-for="l in levels" :key="l.id" class="lv-row">
        <div class="lv-base">
          <b>{{ l.stationCode }}</b>
          <span class="op-meta">{{ l.segmentCode }} · {{ l.createdAt }}</span>
          <span class="lv-level" :style="{ color: l.abnormal === 1 ? '#e53935' : '#0288d1' }">{{ l.measuredLevel }} m</span>
          <span class="op-meta">观测 {{ l.observer }} · 区间 {{ l.lowLevel }}~{{ l.highLevel }}</span>
        </div>
        <div class="lv-derived">
          <span class="derived" title="MeasureWindow 值对象派生">时长 {{ l.durationMinutes }} 分</span>
          <span class="derived" title="LevelBand 值对象派生">变幅 {{ l.bandSpan }} m</span>
          <span
            class="derived"
            :style="{ color: l.safetyGradeColor, borderColor: l.safetyGradeColor }"
            title="LevelBand 值对象派生"
          >{{ l.safetyGradeName }}</span>
          <el-button size="small" plain :loading="loading" @click="refresh(l)">重新派生</el-button>
          <el-button
            v-if="l.abnormal === 1 && l.emergencyOpen"
            size="small" type="warning" plain
            @click="openEmergency(l.emergencyOrderId)"
          >处置单 · {{ l.emergencyStatusName }}</el-button>
          <el-button
            v-else-if="l.abnormal === 1"
            size="small" type="danger" plain :loading="loading"
            @click="createEmergency(l)"
          >发起处置</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.derived-cols { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 8px; }
.derived-col {
  padding: 9px 11px; border-radius: 10px;
  background: var(--primary-wash); border: 1px dashed var(--primary);
}
.dc-head { display: flex; align-items: center; gap: 8px; }
.dc-head code { font-size: 11px; color: var(--primary-deep); }
.dc-owner { font-size: 10.5px; color: var(--ink-soft); margin-top: 3px; }

.chart-block { padding: 8px 0 12px; border-bottom: 1px dashed var(--line); }
.chart-block:last-child { border-bottom: none; }
.chart-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 4px; }
.chart-svg { width: 100%; display: block; }
.pt { cursor: pointer; }
.pt:hover { r: 7; }

.pt-detail {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  margin-top: 6px; padding: 7px 10px; border-radius: 8px;
  background: #f6fcff; border: 1px solid var(--line); font-size: 11.5px;
}

.lv-row {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  padding: 8px 10px; margin-bottom: 7px;
  border-radius: 9px; border: 1px solid var(--line); background: #fff;
}
.lv-base { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; flex: 1 1 340px; }
.lv-level { font-size: 16px; font-weight: 800; }
.lv-derived {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  padding: 5px 8px; border-radius: 8px; background: #f2fbff; border: 1px dashed var(--primary);
}
.op-meta { font-size: 11px; color: var(--ink-dim); }
</style>
