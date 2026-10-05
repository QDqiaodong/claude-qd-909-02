<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const GRADE_ORDER = { TRUNK: 0, BRANCH: 1, LATERAL: 2 }

const topology = ref([])
const loading = ref(false)
const gradeFilter = ref('ALL')
const activeId = ref(null)
const detail = ref(null)

const canals = computed(() => {
  const list = topology.value || []
  return list.slice().sort((a, b) => {
    const la = GRADE_ORDER[a.canalGrade] ?? 3
    const lb = GRADE_ORDER[b.canalGrade] ?? 3
    return la === lb ? (a.id - b.id) : (la - lb)
  })
})

const visible = computed(() =>
  gradeFilter.value === 'ALL'
    ? canals.value
    : canals.value.filter((c) => c.canalGrade === gradeFilter.value)
)

// ---- 纵向水系拓扑图布局：三级渠道各占一列，层级越深越往下错开，做成阶梯 ----
const COL_W = 168
const NODE_H = 74
const TOP_Y = 96
const LEVEL_DROP = 46

const laid = computed(() => {
  const out = []
  visible.value.forEach((canal, col) => {
    const level = GRADE_ORDER[canal.canalGrade] ?? 2
    const x = 92 + col * COL_W
    const startY = TOP_Y + level * LEVEL_DROP
    const segs = canal.segments || []
    const nodes = segs.map((seg, i) => ({
      seg,
      x,
      y: startY + i * NODE_H,
      cx: x,
      cy: startY + i * NODE_H
    }))
    out.push({
      canal,
      level,
      x,
      startY,
      endY: startY + Math.max(0, segs.length - 1) * NODE_H,
      nodes
    })
  })
  return out
})

const nodeIndex = computed(() => {
  const map = {}
  laid.value.forEach((c) => c.nodes.forEach((n) => { map[n.seg.id] = n }))
  return map
})

// 支渠 / 斗渠的取水口写在其 source_name 里（如「东干渠 3+200 分水口」），据此画跨渠道虚线
const branches = computed(() =>
  laid.value
    .filter((c) => c.level > 0)
    .map((c) => {
      const source = c.canal.sourceName || ''
      const parent = laid.value.find(
        (p) => p.level === c.level - 1 && p.canal.canalName && source.indexOf(p.canal.canalName) >= 0
      )
      return parent ? { from: parent, to: c } : null
    })
    .filter(Boolean)
)

const svgHeight = computed(() => {
  const max = laid.value.reduce((m, c) => Math.max(m, c.endY), TOP_Y)
  return max + 110
})

const svgWidth = computed(() => Math.max(760, 92 + laid.value.length * COL_W + 40))

function nodeFill(seg) {
  if (activeId.value === seg.id) return seg.canalGradeColor || '#0288d1'
  return '#ffffff'
}

function nodeText(seg) {
  return activeId.value === seg.id ? '#ffffff' : '#10304a'
}

async function pickNode(seg) {
  if (seg.disabled) {
    ElMessage.warning('渠段 ' + seg.segmentCode + ' 已停用，不参与配水')
  }
  activeId.value = seg.id
  loading.value = true
  try {
    detail.value = await api.canal.segmentDetail(seg.id)
  } catch (e) {
    ElMessage.error(e.message)
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    topology.value = await api.canal.topology()
    if (activeId.value === null) {
      const first = laid.value[0]?.nodes?.[0]?.seg
      if (first) await pickNode(first)
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function flowRate(seg) {
  const design = Number(seg.designFlow || 0)
  const used = Number(seg.allottedFlow || 0)
  if (!design) return 0
  return Math.min(1, used / design)
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">渠系 · 纵向水系拓扑图</span>
      <span class="view-tip">干渠 → 支渠 → 斗渠三级连线，点节点看该渠段的闸门 / 量测 / 配水</span>
    </div>

    <div class="chip-row">
      <span class="chip" :class="{ on: gradeFilter === 'ALL' }" @click="gradeFilter = 'ALL'">全部渠系</span>
      <span class="chip" :class="{ on: gradeFilter === 'TRUNK' }" @click="gradeFilter = 'TRUNK'">干渠</span>
      <span class="chip" :class="{ on: gradeFilter === 'BRANCH' }" @click="gradeFilter = 'BRANCH'">支渠</span>
      <span class="chip" :class="{ on: gradeFilter === 'LATERAL' }" @click="gradeFilter = 'LATERAL'">斗渠</span>
    </div>

    <div class="grid-2">
      <div class="panel">
        <div class="panel-title">水系拓扑（纵向）</div>
        <div v-if="!laid.length" class="empty-tip">暂无渠系数据</div>
        <svg v-else class="topo-svg" :viewBox="`0 0 ${svgWidth} ${svgHeight}`" :height="svgHeight">
          <!-- 跨渠道分水虚线 -->
          <template v-for="(b, i) in branches" :key="'b' + i">
            <path
              :d="`M ${b.from.x} ${b.from.endY + 26} C ${b.from.x} ${b.to.startY - 40}, ${b.to.x} ${b.to.startY - 40}, ${b.to.x} ${b.to.startY - 26}`"
              fill="none" stroke="#90a4ae" stroke-width="1.6" stroke-dasharray="6 5"
            />
          </template>

          <template v-for="c in laid" :key="c.canal.id">
            <!-- 渠道主干（纵向），线宽按等级 -->
            <line
              :x1="c.x" :y1="c.startY - 22" :x2="c.x" :y2="c.endY + 22"
              :stroke="c.canal.canalGradeColor || '#0288d1'"
              :stroke-width="c.canal.canalGradeStroke || 3"
              stroke-linecap="round" opacity="0.55"
            />
            <!-- 渠道头 -->
            <g>
              <rect
                :x="c.x - 76" :y="c.startY - 66" width="152" height="30" rx="8"
                :fill="c.canal.canalGradeColor || '#0288d1'"
              />
              <text :x="c.x" :y="c.startY - 46" text-anchor="middle" fill="#fff" font-size="12.5" font-weight="700">
                {{ c.canal.canalName }} · {{ c.canal.canalGradeName }}
              </text>
            </g>

            <template v-for="(n, idx) in c.nodes" :key="n.seg.id">
              <!-- 上下游衔接线 -->
              <line
                v-if="idx > 0"
                :x1="c.x" :y1="n.y - NODE_H + 22" :x2="c.x" :y2="n.y - 22"
                :stroke="n.seg.canalGradeColor || '#0288d1'" stroke-width="2.4"
              />
              <!-- 渠段节点 -->
              <g class="topo-node" :class="{ on: activeId === n.seg.id, off: n.seg.disabled }" @click="pickNode(n.seg)">
                <rect
                  :x="n.x - 70" :y="n.y - 22" width="140" height="44" rx="10"
                  :fill="nodeFill(n.seg)"
                  :stroke="n.seg.canalGradeColor || '#0288d1'"
                  :stroke-width="activeId === n.seg.id ? 2.6 : 1.6"
                />
                <text :x="n.x" :y="n.y - 4" text-anchor="middle" :fill="nodeText(n.seg)" font-size="12" font-weight="700">
                  {{ n.seg.segmentCode }}
                </text>
                <text :x="n.x" :y="n.y + 13" text-anchor="middle" :fill="nodeText(n.seg)" font-size="10.5" :opacity="activeId === n.seg.id ? 0.95 : 0.72">
                  {{ n.seg.lengthKm }}km · {{ n.seg.designFlow }}m³/s
                </text>
              </g>
            </template>
          </template>
        </svg>
      </div>

      <div class="panel" v-if="detail">
        <div class="panel-title">
          渠段详情 · {{ detail.segment.segmentName }}
          <span class="tag-line" :style="{ color: detail.segment.statusColor }">
            <i class="dot" :style="{ background: detail.segment.statusColor }"></i>{{ detail.segment.statusName }}
          </span>
        </div>

        <div class="detail-grid">
          <div class="mini-card"><div class="k">桩号里程</div><div class="v">{{ detail.segment.startMileage }} ~ {{ detail.segment.endMileage }} km</div></div>
          <div class="mini-card"><div class="k">设计流量</div><div class="v">{{ detail.segment.designFlow }} m³/s</div></div>
          <div class="mini-card"><div class="k">上游渠段</div><div class="v" style="font-size:14px">{{ detail.segment.upstreamCode || '首段' }}</div></div>
          <div class="mini-card"><div class="k">衬砌型式</div><div class="v" style="font-size:14px">{{ detail.segment.liningTypeName }}</div></div>
        </div>

        <div class="load-line">
          <span>当日已排配水 {{ detail.segment.allottedFlow }} / 设计 {{ detail.segment.designFlow }} m³/s</span>
          <div class="load-bar" :class="{ full: flowRate(detail.segment) >= 1 }">
            <i :style="{ width: (flowRate(detail.segment) * 100).toFixed(1) + '%' }"></i>
          </div>
        </div>

        <div class="sub-title">闸门（{{ detail.gates.length }}）</div>
        <div v-if="!detail.gates.length" class="empty-tip" style="padding:10px">该渠段暂无闸门</div>
        <div v-for="g in detail.gates" :key="g.id" class="sub-row">
          <span class="tag-line" :style="{ color: g.gateTypeColor }">
            <i class="dot" :style="{ background: g.gateTypeColor }"></i>{{ g.gateTypeName }}
          </span>
          <b>{{ g.gateName }}</b>
          <span class="sub-meta">开度 {{ g.currentOpening }}%（安全区 {{ g.safeMinOpening }}~{{ g.safeMaxOpening }}）</span>
        </div>

        <div class="sub-title">水位量测（{{ detail.levels.length }}）</div>
        <div v-if="!detail.levels.length" class="empty-tip" style="padding:10px">暂无量测记录</div>
        <div v-for="l in detail.levels" :key="l.id" class="sub-row">
          <b>{{ l.stationCode }}</b>
          <span class="sub-meta">{{ l.measuredLevel }} m</span>
          <span class="derived" title="由 MeasureWindow 值对象派生">时长 {{ l.durationMinutes }} 分</span>
          <span class="derived" title="由 LevelBand 值对象派生">变幅 {{ l.bandSpan }} m</span>
        </div>

        <div class="sub-title">配水安排（{{ detail.allotments.length }}）</div>
        <div v-if="!detail.allotments.length" class="empty-tip" style="padding:10px">暂无配水安排</div>
        <div v-for="a in detail.allotments" :key="a.id" class="sub-row">
          <b>{{ a.allotDate }}</b>
          <span class="sub-meta">{{ a.flow }} m³/s × {{ a.hours }}h = {{ a.volumeWan }} 万m³</span>
          <span class="tag-line">{{ a.statusName }}</span>
        </div>
      </div>

      <div class="panel" v-else>
        <div class="empty-tip">点左侧拓扑图上的渠段节点查看详情</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.topo-svg { width: 100%; display: block; }
.topo-node { cursor: pointer; }
.topo-node:hover rect { filter: drop-shadow(0 4px 10px rgba(2, 136, 209, 0.30)); }
.topo-node.off { opacity: 0.5; }

.detail-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.detail-grid .v { font-size: 16px; }

.load-line { margin: 10px 0 4px; font-size: 11.5px; color: var(--ink-soft); }
.load-bar { height: 6px; border-radius: 4px; background: #e3f2fd; overflow: hidden; margin-top: 5px; }
.load-bar i { display: block; height: 100%; border-radius: 4px; background: linear-gradient(90deg, #4fc3f7, var(--primary)); }
.load-bar.full i { background: linear-gradient(90deg, #f9a825, var(--danger)); }

.sub-title { margin: 12px 0 6px; font-size: 12px; font-weight: 700; color: var(--primary-deep); }
.sub-row {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 7px 10px; margin-bottom: 6px;
  border-radius: 8px; background: #f6fcff; border: 1px solid var(--line);
}
.sub-meta { font-size: 11px; color: var(--ink-dim); }
</style>
