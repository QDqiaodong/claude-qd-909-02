<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const APPROVE_THRESHOLD = 30 // 与后端 Dict.APPROVE_THRESHOLD 一致：超过即走审批

const gates = ref([])
const operations = ref([])
const loading = ref(false)
const activeId = ref(null)
const target = ref(0)
const form = ref({ operationNo: '', operator: '值班员', reason: '', note: '' })

const current = computed(() => gates.value.find((g) => g.id === activeId.value) || null)

const currentOpening = computed(() => Number(current.value?.currentOpening ?? 0))
const safeMin = computed(() => Number(current.value?.safeMinOpening ?? 0))
const safeMax = computed(() => Number(current.value?.safeMaxOpening ?? 100))
const adjustRange = computed(() => Math.abs(target.value - currentOpening.value).toFixed(2))
const needApprove = computed(() => Number(adjustRange.value) > APPROVE_THRESHOLD)
const inSafeBand = computed(() => target.value >= safeMin.value && target.value <= safeMax.value)

// 安全开度区色带：安全区外左右两段是警示色
const bandStyle = computed(() => {
  const lo = safeMin.value
  const hi = safeMax.value
  return {
    background: `linear-gradient(90deg,
      #f9a825 0%, #f9a825 ${lo}%,
      #43a047 ${lo}%, #43a047 ${hi}%,
      #e53935 ${hi}%, #e53935 100%)`
  }
})

function pad(n) { return String(n).padStart(2, '0') }

function nowText() {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function nextOperationNo() {
  const d = new Date()
  return `OP-${d.getFullYear()}-${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
}

async function select(gate) {
  activeId.value = gate.id
  target.value = Number(gate.currentOpening ?? 0)
  form.value.operationNo = nextOperationNo()
  await loadOperations()
}

async function loadOperations() {
  if (!activeId.value) return
  try {
    operations.value = await api.gate.operations(activeId.value)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function submitAdjust() {
  if (!current.value) return
  const payload = {
    gateId: current.value.id,
    operationNo: form.value.operationNo || nextOperationNo(),
    afterOpening: Number(target.value),
    operateTime: nowText(),
    operator: form.value.operator || '值班员',
    reason: form.value.reason || '',
    note: form.value.note || ''
  }
  if (payload.afterOpening === currentOpening.value) {
    ElMessage.warning('目标开度与当前开度一致，无需调节')
    return
  }
  // 单次调节超过安全幅度：先弹二次确认，确认后才以 confirmed=true 提交（后端置待审批）
  if (needApprove.value) {
    try {
      await ElMessageBox.confirm(
        `本次调节幅度 ${adjustRange.value} 个百分点，超过 ${APPROVE_THRESHOLD} 个百分点，` +
        `且目标开度${inSafeBand.value ? '' : '不在'}安全区（${safeMin.value}~${safeMax.value}%）内。` +
        `确认提交后该操作进入「待审批」，审批通过才落开度。`,
        '超安全幅度 · 二次确认',
        { type: 'warning', confirmButtonText: '确认提交并进入审批', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }
  loading.value = true
  try {
    await api.gate.adjust(payload, needApprove.value)
    ElMessage.success(needApprove.value ? '已提交，进入待审批' : '开度已调节')
    await load()
    await loadOperations()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function approve(op) {
  loading.value = true
  try {
    await api.gate.approve(op.id, '灌区调度')
    ElMessage.success('操作已批准并落开度')
    await load()
    await loadOperations()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function reject(op) {
  let reason = ''
  try {
    const res = await ElMessageBox.prompt('请填写驳回理由', '驳回开度操作', {
      inputPlaceholder: '如：超安全开度上限，易冲刷',
      inputValidator: (v) => (v && String(v).trim().length > 0) || '驳回理由不能为空'
    })
    reason = res.value
  } catch {
    return
  }
  loading.value = true
  try {
    await api.gate.reject(op.id, '灌区调度', reason)
    ElMessage.success('操作已驳回')
    await loadOperations()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    gates.value = await api.gate.panel()
    if (activeId.value === null && gates.value.length) {
      await select(gates.value[0])
    }
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
      <span class="view-title">闸门 · 开度调节与安全区</span>
      <span class="view-tip">拖动大滑杆定目标开度；单次调节超过 {{ APPROVE_THRESHOLD }} 个百分点需二次确认并走审批</span>
    </div>

    <div class="grid-2">
      <div class="panel">
        <div class="panel-title">闸门列表（{{ gates.length }}）</div>
        <div v-if="!gates.length" class="empty-tip">暂无闸门</div>
        <div
          v-for="g in gates"
          :key="g.id"
          class="gate-card"
          :class="{ on: g.id === activeId, off: g.disabled }"
          @click="select(g)"
        >
          <div class="gc-head">
            <b>{{ g.gateName }}</b>
            <span class="tag-line" :style="{ color: g.gateTypeColor }">
              <i class="dot" :style="{ background: g.gateTypeColor }"></i>{{ g.gateTypeName }}
            </span>
          </div>
          <div class="gc-meta">{{ g.gateCode }} · {{ g.segmentName }} · {{ g.statusName }}</div>
          <div class="gc-open">
            <b :style="{ color: g.inSafeBand ? 'var(--safe)' : 'var(--danger)' }">{{ g.currentOpening }}%</b>
            <span>安全区 {{ g.safeMinOpening }} ~ {{ g.safeMaxOpening }}%</span>
            <span class="gc-flow">估算 {{ g.estimateFlow }} m³/s</span>
          </div>
          <div v-if="g.pendingCount" class="gc-pending">{{ g.pendingCount }} 条待审批</div>
        </div>
      </div>

      <div class="panel" v-if="current">
        <div class="panel-title">
          {{ current.gateName }} · 开度滑杆
          <span class="tag-line" :style="{ color: current.gateTypeColor }">
            <i class="dot" :style="{ background: current.gateTypeColor }"></i>{{ current.gateTypeName }}
          </span>
        </div>

        <!-- 安全开度区色带 -->
        <div class="band-wrap">
          <div class="band" :style="bandStyle">
            <span class="band-tick" :style="{ left: safeMin + '%' }"></span>
            <span class="band-tick" :style="{ left: safeMax + '%' }"></span>
            <span class="band-now" :style="{ left: currentOpening + '%' }" :title="'当前 ' + currentOpening + '%'"></span>
            <span class="band-target" :style="{ left: target + '%' }"></span>
          </div>
          <div class="band-scale">
            <span>0%</span>
            <span class="bs-safe">安全区 {{ safeMin }}% ~ {{ safeMax }}%</span>
            <span>100%</span>
          </div>
        </div>

        <div class="slider-line">
          <span class="sl-label">目标开度</span>
          <el-slider v-model="target" :min="0" :max="100" :step="0.5" show-input :show-input-controls="false" class="sl" />
        </div>

        <div class="range-row">
          <div class="mini-card">
            <div class="k">调节幅度</div>
            <div class="v" :style="{ color: needApprove ? 'var(--danger)' : 'var(--primary-deep)' }">
              {{ adjustRange }} 个百分点
            </div>
          </div>
          <div class="mini-card">
            <div class="k">目标是否在安全区</div>
            <div class="v" :style="{ color: inSafeBand ? 'var(--safe)' : 'var(--danger)' }">
              {{ inSafeBand ? '在安全区内' : '越出安全区' }}
            </div>
          </div>
          <div class="mini-card">
            <div class="k">提交后状态</div>
            <div class="v" style="font-size:15px">{{ needApprove ? '待审批' : '已执行' }}</div>
          </div>
        </div>

        <div class="form-row">
          <el-input v-model="form.operationNo" placeholder="操作单号" style="width:190px" />
          <el-input v-model="form.operator" placeholder="操作人" style="width:130px" />
          <el-input v-model="form.reason" placeholder="调节事由" style="flex:1;min-width:180px" />
          <el-button type="primary" :loading="loading" @click="submitAdjust">
            {{ needApprove ? '二次确认并提交审批' : '提交调节' }}
          </el-button>
        </div>
        <div v-if="needApprove" class="warn-line">
          幅度 {{ adjustRange }} > {{ APPROVE_THRESHOLD }} 个百分点：提交时会弹二次确认，通过后该操作进入待审批，审批通过才落开度。
        </div>
      </div>

      <div class="panel" v-else><div class="empty-tip">请选择左侧闸门</div></div>
    </div>

    <div class="panel">
      <div class="panel-title">操作记录（{{ operations.length }}）· 待审批可直接批准 / 驳回</div>
      <div v-if="!operations.length" class="empty-tip">该闸门暂无操作记录</div>
      <div v-for="op in operations" :key="op.id" class="op-row">
        <span class="tag-line" :style="{ color: op.statusColor }">
          <i class="dot" :style="{ background: op.statusColor }"></i>{{ op.statusName }}
        </span>
        <b>{{ op.operationNo }}</b>
        <span class="op-meta">{{ op.beforeOpening }}% → {{ op.afterOpening }}%（幅度 {{ op.adjustRange }}）</span>
        <span class="op-meta">{{ op.operateTime }} · {{ op.operator }}</span>
        <span v-if="op.needApprove" class="derived" title="超过 30 个百分点阈值">超幅审批</span>
        <span v-if="op.emergencyOrderId" class="linked-emergency">险情处置 #{{ op.emergencyOrderId }}</span>
        <span class="op-reason">{{ op.reason || '—' }}</span>
        <span v-if="op.status === 'PENDING'" class="op-actions">
          <el-button size="small" type="primary" plain @click="approve(op)">批准并落开度</el-button>
          <el-button size="small" type="danger" plain @click="reject(op)">驳回</el-button>
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.gate-card {
  padding: 10px 12px;
  margin-bottom: 9px;
  border-radius: 10px;
  border: 1px solid var(--line-strong);
  background: #fff;
  cursor: pointer;
  transition: all .18s ease;
}
.gate-card:hover { border-color: var(--primary); box-shadow: 0 6px 14px rgba(2, 136, 209, 0.16); }
.gate-card.on { border-color: var(--primary); background: var(--primary-wash); box-shadow: 0 0 0 2px rgba(2, 136, 209, 0.18); }
.gate-card.off { opacity: 0.55; }
.gc-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.gc-meta { font-size: 11px; color: var(--ink-dim); margin-top: 3px; }
.gc-open { margin-top: 6px; display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; font-size: 11.5px; color: var(--ink-soft); }
.gc-open b { font-size: 19px; }
.gc-flow { margin-left: auto; color: var(--primary-deep); font-weight: 700; }
.gc-pending {
  margin-top: 6px; font-size: 11px; font-weight: 700; color: #8d6e00;
  background: #fff8e1; border: 1px solid #ffe082; border-radius: 6px; padding: 2px 8px; display: inline-block;
}

/* 安全区色带 */
.band-wrap { margin: 4px 0 10px; }
.band { position: relative; height: 34px; border-radius: 8px; overflow: visible; }
.band-tick { position: absolute; top: -3px; bottom: -3px; width: 2px; background: #fff; box-shadow: 0 0 0 1px rgba(0,0,0,.18); }
.band-now {
  position: absolute; top: -8px; width: 0; height: 0;
  border-left: 6px solid transparent; border-right: 6px solid transparent; border-top: 9px solid #01579b;
  transform: translateX(-6px);
}
.band-target {
  position: absolute; bottom: -9px; width: 3px; height: 16px; border-radius: 2px;
  background: #10304a; transform: translateX(-1.5px);
}
.band-scale { display: flex; justify-content: space-between; font-size: 10.5px; color: var(--ink-dim); margin-top: 12px; }
.bs-safe { color: var(--safe); font-weight: 700; }

.slider-line { display: flex; align-items: center; gap: 12px; margin: 6px 0 12px; }
.sl-label { flex: 0 0 auto; font-size: 12px; font-weight: 700; color: var(--ink-soft); }
.sl { flex: 1 1 auto; }

.range-row { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.range-row .v { font-size: 16px; }

.form-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-top: 12px; }
.warn-line {
  margin-top: 8px; padding: 7px 10px; border-radius: 8px; font-size: 11.5px;
  color: #8d6e00; background: #fff8e1; border: 1px solid #ffe082;
}

.op-row {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 7px 10px; margin-bottom: 6px;
  border-radius: 8px; background: #f6fcff; border: 1px solid var(--line);
}
.op-meta { font-size: 11px; color: var(--ink-dim); }
.op-reason { font-size: 11.5px; color: var(--ink-soft); }
.linked-emergency {
  font-size: 10px; font-weight: 700; color: #fff; background: #7e57c2;
  border-radius: 999px; padding: 2px 8px;
}
.op-actions { margin-left: auto; display: flex; gap: 6px; }
</style>
