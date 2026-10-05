<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import router from '../router'

const loading = ref(false)
const orders = ref([])
const levels = ref([])
const currentId = ref(null)
const detail = ref(null)
const currentOperator = ref('值班员甲')

const operators = ['值班员甲', '值班员乙', '灌区调度']
const freshChecked = ref(false)
const observeForm = ref({ comment: '', nextReviewAt: '' })
const gateForm = ref({
  gateId: null,
  afterOpening: 0,
  reason: '',
  comment: '',
  operationNo: ''
})
const reviewForm = ref({ comment: '' })
const resolveForm = ref({ note: '' })

const order = computed(() => detail.value?.order || null)
const abnormalLevels = computed(() => (levels.value || []).filter((x) => x.abnormal === 1))
const changes = computed(() => detail.value?.changes || [])
const stale = computed(() => Boolean(detail.value?.stale && changes.value.length))
const timeline = computed(() => detail.value?.timeline || [])
const followingLevels = computed(() => detail.value?.followingLevels || [])
const followingOperations = computed(() => detail.value?.gateOperations || [])
const currentGates = computed(() => detail.value?.snapshotGates || [])
const selectedGate = computed(() => currentGates.value.find((g) => g.gateId === gateForm.value.gateId) || null)
const isHandler = computed(() => order.value?.handler && order.value.handler === currentOperator.value)
const canAct = computed(() => order.value?.open && isHandler.value)
const gateRange = computed(() => {
  const g = selectedGate.value
  return {
    min: Number(g?.safeMinOpening ?? 0),
    max: Number(g?.safeMaxOpening ?? 100),
    current: Number(g?.currentOpening ?? 0),
    range: Math.abs(Number(gateForm.value.afterOpening || 0) - Number(g?.currentOpening || 0)).toFixed(2),
    needApprove: Math.abs(Number(gateForm.value.afterOpening || 0) - Number(g?.currentOpening || 0)) > 30
  }
})

async function load() {
  loading.value = true
  try {
    const [list, levelList] = await Promise.all([api.emergency.list(), api.level.vo()])
    orders.value = list || []
    levels.value = levelList || []
    const query = router.currentRoute.value?.query || {}
    const targetOrder = orders.value.find((o) => String(o.id) === String(query.orderId))
      || orders.value.find((o) => String(o.waterLevelId) === String(query.levelId))
    if (currentId.value || targetOrder) {
      if (targetOrder) currentId.value = targetOrder.id
      detail.value = await api.emergency.detail(currentId.value)
      initGateForm(detail.value)
    } else if (orders.value.length) {
      await select(orders.value[0])
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function select(item) {
  currentId.value = item.id
  loading.value = true
  try {
    detail.value = await api.emergency.detail(item.id)
    freshChecked.value = false
    initGateForm(detail.value)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function initGateForm(d) {
  const first = d?.snapshotGates?.find((g) => g.operable) || d?.snapshotGates?.[0]
  gateForm.value.gateId = first?.gateId || null
  gateForm.value.afterOpening = Number(first?.currentOpening || 0)
}

async function createOrder(level) {
  loading.value = true
  try {
    const d = await api.emergency.create({ waterLevelId: level.id, creator: currentOperator.value })
    ElMessage.success('处置单已发起，状态：待接管')
    router.replace({ path: '/emergency', query: { levelId: level.id } })
    await load()
    currentId.value = d.order.id
    detail.value = d
    initGateForm(d)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function take() {
  await runAction(() => api.emergency.take(currentId.value, { operator: currentOperator.value }), '接管成功')
}

async function refreshSnapshot() {
  await runAction(() => api.emergency.refresh(currentId.value, {
    operator: currentOperator.value,
    version: order.value.version
  }), '已刷新快照')
}

function basePayload() {
  return { operator: currentOperator.value, version: order.value.version, fresh: freshChecked.value }
}

async function submitObserve() {
  if (!observeForm.value.nextReviewAt) return ElMessage.warning('请选择复查时限')
  if (!observeForm.value.comment) return ElMessage.warning('请填写继续观测意见')
  await runAction(() => api.emergency.observe(currentId.value, {
    ...basePayload(),
    nextReviewAt: observeForm.value.nextReviewAt,
    comment: observeForm.value.comment
  }), '继续观测意见已记录')
}

async function submitGate() {
  if (!selectedGate.value) return ElMessage.warning('请选择闸门')
  if (!gateForm.value.comment) return ElMessage.warning('请填写闸门调整处置意见')
  await runAction(() => api.emergency.gate(currentId.value, {
    ...basePayload(),
    gateId: gateForm.value.gateId,
    afterOpening: Number(gateForm.value.afterOpening),
    reason: gateForm.value.reason || `险情 ${order.value.orderNo} 调整`,
    comment: gateForm.value.comment,
    operationNo: gateForm.value.operationNo || null
  }), gateRange.value.needApprove ? '闸门调整已进入审批，时间线已关联' : '闸门调整已执行并写入时间线')
}

async function withdraw() {
  let reason = ''
  try {
    const res = await ElMessageBox.prompt('撤回调整后已填意见仍会保留，可刷新后改提方案。请填写撤回原因。', '撤回闸门调整', {
      inputValidator: (v) => (v && v.trim()) || '撤回原因不能为空'
    })
    reason = res.value
  } catch { return }
  await runAction(() => api.emergency.withdraw(currentId.value, {
    operator: currentOperator.value,
    version: order.value.version,
    reason
  }), '闸门调整已撤回')
}

async function submitReview() {
  if (!reviewForm.value.comment) return ElMessage.warning('请填写提交复核意见')
  await runAction(() => api.emergency.review(currentId.value, {
    ...basePayload(),
    comment: reviewForm.value.comment
  }), '已提交待复核')
}

async function resolve() {
  if (!resolveForm.value.note) return ElMessage.warning('请填写复核解除意见')
  await runAction(() => api.emergency.resolve(currentId.value, {
    ...basePayload(),
    resolutionNote: resolveForm.value.note
  }), '险情已解除')
}

async function manual() {
  let reason = ''
  try {
    const res = await ElMessageBox.prompt('转人工后自动流程结束，时间线仍完整保留。请填写原因。', '转人工推进', {
      inputValidator: (v) => (v && v.trim()) || '转人工原因不能为空'
    })
    reason = res.value
  } catch { return }
  await runAction(() => api.emergency.manual(currentId.value, {
    operator: currentOperator.value,
    version: order.value.version,
    reason
  }), '已转人工推进')
}

async function runAction(action, successText) {
  loading.value = true
  try {
    detail.value = await action()
    freshChecked.value = false
    initGateForm(detail.value)
    ElMessage.success(successText)
  } catch (e) {
    if (e.status === 409 && e.data) {
      const keptGate = gateForm.value.gateId
      const keptOpening = gateForm.value.afterOpening
      const keptReason = gateForm.value.reason
      const keptComment = gateForm.value.comment
      detail.value = e.data
      gateForm.value.gateId = keptGate
      gateForm.value.afterOpening = keptOpening
      gateForm.value.reason = keptReason
      gateForm.value.comment = keptComment
      freshChecked.value = false
      ElMessageBox.alert(
        `快照已过期，已为你列出水位/渠段/闸门变化。页面上的意见和目标开度未被覆盖，可选择“刷新后继续”、撤回调整或转人工。`,
        '处置依据已变化',
        { type: 'warning', confirmButtonText: '知道了' }
      )
    } else {
      ElMessage.error(e.message)
      try { detail.value = await api.emergency.detail(currentId.value) } catch (_) { /* 保留当前页 */ }
    }
  } finally {
    loading.value = false
  }
}

function statusTagClass(s) {
  return {
    PENDING: 'tag-danger',
    HANDLING: 'tag-warn',
    REVIEW: 'tag-info',
    RESOLVED: 'tag-ok',
    MANUAL: 'tag-manual'
  }[s] || ''
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">异常水位险情处置台</span>
      <span class="view-tip">异常水位 → 待接管 → 处置中 → 待复核 → 已解除 / 转人工；同渠段同时段互斥、接管互斥、提交前强制核对快照</span>
    </div>

    <div class="emergency-grid">
      <div class="panel">
        <div class="panel-title">
          处置单（{{ orders.length }}）
          <el-select v-model="currentOperator" size="small" style="width:128px;margin-left:auto">
            <el-option v-for="x in operators" :key="x" :label="x" :value="x" />
          </el-select>
        </div>
        <div v-if="!orders.length" class="empty-tip">暂无处置单，可从下方异常水位发起</div>
        <div
          v-for="o in orders"
          :key="o.id"
          class="order-card"
          :class="{ on: currentId === o.id }"
          @click="select(o)"
        >
          <div class="oc-row">
            <b>{{ o.orderNo }}</b>
            <span class="status-pill" :class="statusTagClass(o.status)">{{ o.statusName }}</span>
          </div>
          <div class="oc-meta">{{ o.segmentName }} · {{ o.stationCode }} · {{ o.sourceWindowStart }}</div>
          <div class="oc-level">实测 {{ o.sourceMeasuredLevel }} m</div>
          <div class="oc-meta">
            {{ o.handler ? `接管人：${o.handler}` : '尚待接管' }}
            <span v-if="o.reviewDue" class="due">复查已到时限</span>
          </div>
        </div>

        <div class="abnormal-title">异常水位记录（{{ abnormalLevels.length }}）</div>
        <div v-for="l in abnormalLevels" :key="l.id" class="level-mini">
          <div>
            <b>{{ l.stationCode }}</b>
            <span class="oc-meta">{{ l.segmentCode }} · {{ l.startAt }}</span>
            <div class="danger-text">{{ l.abnormalReason }}</div>
          </div>
          <el-button
            v-if="l.emergencyOpen"
            size="small"
            plain
            type="warning"
            @click="orders.find(x => x.id === l.emergencyOrderId) && select(orders.find(x => x.id === l.emergencyOrderId))"
          >已发单 · {{ l.emergencyStatusName }}</el-button>
          <el-button v-else size="small" type="danger" plain :loading="loading" @click="createOrder(l)">发起处置</el-button>
        </div>
      </div>

      <div class="panel detail-panel" v-if="detail && order">
        <div class="detail-head">
          <div>
            <b>{{ order.orderNo }}</b>
            <span class="status-pill big" :class="statusTagClass(order.status)">{{ order.statusName }}</span>
          </div>
          <div class="oc-meta">版本 v{{ order.version }} · 创建 {{ order.createdAt }} · 更新 {{ order.updatedAt }}</div>
        </div>

        <div v-if="stale" class="stale-box">
          <b>快照已过期，以下依据发生变化（表单内容已保留，未静默覆盖）</b>
          <div v-for="(c, i) in changes" :key="i" class="change-row">
            <span class="change-label">{{ c.label }}</span>
            <span>快照：{{ c.snapshotValue }}</span>
            <span>当前：{{ c.currentValue }}</span>
            <em>{{ c.detail }}</em>
          </div>
          <div class="stale-actions">
            <el-button size="small" type="primary" :loading="loading" @click="refreshSnapshot">刷新后继续</el-button>
            <el-button v-if="order.status === 'HANDLING'" size="small" type="warning" @click="withdraw">撤回调整</el-button>
            <el-button size="small" type="info" @click="manual">转人工</el-button>
          </div>
        </div>

        <div class="snapshot-grid">
          <div class="snap-card">
            <div class="snap-title">发起时量测区间与水位</div>
            <div>{{ order.sourceWindowStart }} ~ {{ order.sourceWindowEnd }}</div>
            <div class="snap-level">{{ order.sourceMeasuredLevel }} m</div>
            <div class="oc-meta">区间 {{ order.sourceLowLevel }} ~ {{ order.sourceHighLevel }} m</div>
            <div class="danger-text">{{ order.sourceAbnormalReason }}</div>
          </div>
          <div class="snap-card">
            <div class="snap-title">发起时渠段状态</div>
            <div>{{ order.segmentName }}（{{ order.segmentCode }}）</div>
            <div>{{ order.sourceSegmentStatusName }}</div>
            <div class="oc-meta">接管人：{{ order.handler || '—' }}；接管：{{ order.takenOverAt || '—' }}</div>
          </div>
          <div class="snap-card">
            <div class="snap-title">最新水位上下文</div>
            <template v-if="detail.latest">
              <div>v{{ detail.latest.versionNo }} · #{{ detail.latest.waterLevelId }}</div>
              <div class="snap-level" :class="{ 'danger-text': detail.latest.abnormal === 1 }">
                {{ detail.latest.measuredLevel }} m
              </div>
              <div class="oc-meta">渠段：{{ detail.latest.segmentStatusName }} · {{ detail.latest.createdAt }}</div>
            </template>
            <div v-else class="empty-tip">暂无上下文</div>
          </div>
        </div>

        <div class="gate-snapshot-list">
          <div class="snap-title">关联闸门快照 vs 当前运行状态</div>
          <div v-for="g in currentGates" :key="g.gateId" class="gate-snap-row" :class="{ changed: g.changed }">
            <b>{{ g.gateCode }} · {{ g.gateName }}</b>
            <span>快照 {{ g.snapshotStatusName }} / {{ g.snapshotOpening }}%</span>
            <span>当前 {{ g.currentStatusName }} / {{ g.currentOpening }}%</span>
            <span>安全区 {{ g.safeMinOpening }}~{{ g.safeMaxOpening }}%</span>
            <span>快照操作 {{ g.snapshotOperationNo || '无' }}</span>
            <span>最新操作 {{ g.currentOperationNo || '无' }}（{{ g.currentOperationStatus || '—' }}）</span>
            <span v-for="w in g.warnings" :key="w" class="danger-text">{{ w }}</span>
          </div>
        </div>

        <div class="action-box" v-if="order.status === 'PENDING'">
          <div class="warn-line">该处置单尚未被接管。只有第一位成功提交的值班员能成为接管人；第二人刷新后会看到一致状态。</div>
          <el-button type="primary" :loading="loading" @click="take">我（{{ currentOperator }}）接管</el-button>
        </div>

        <template v-if="order.open && order.status !== 'PENDING'">
          <div v-if="!isHandler" class="warn-line danger-bg">
            该处置单已由 <b>{{ order.handler }}</b> 占用，当前值班员 {{ currentOperator }} 不能操作。可刷新查看状态，但不能重复处置。
          </div>

          <div class="action-box" v-if="canAct">
            <div class="action-head">
              <b>处置操作</b>
              <el-button size="small" plain :loading="loading" @click="refreshSnapshot">核对并刷新最新水位/闸门</el-button>
              <el-button size="small" plain type="info" :loading="loading" @click="manual">转人工推进</el-button>
            </div>
            <el-checkbox v-model="freshChecked">我已核对最新水位、渠段状态、闸门运行状态、安全开度区间和操作记录，按刷新后的依据提交</el-checkbox>

            <div class="decision-grid">
              <div class="decision-card" v-if="order.status === 'HANDLING'">
                <div class="snap-title">继续观测（需复查时限）</div>
                <el-date-picker
                  v-model="observeForm.nextReviewAt"
                  type="datetime"
                  placeholder="复查时限"
                  value-format="YYYY-MM-DDTHH:mm:ss"
                  style="width:100%"
                />
                <el-input v-model="observeForm.comment" type="textarea" :rows="2" placeholder="观测意见：如维持现有开度，30 分钟后复查" />
                <el-button type="primary" plain :loading="loading" :disabled="!freshChecked" @click="submitObserve">提交继续观测</el-button>
              </div>

              <div class="decision-card wide" v-if="order.status === 'HANDLING'">
                <div class="snap-title">提出闸门调整（按当前闸门状态与安全区间）</div>
                <div class="gate-form-row">
                  <el-select v-model="gateForm.gateId" placeholder="选择关联闸门" style="width:220px">
                    <el-option
                      v-for="g in currentGates"
                      :key="g.gateId"
                      :label="`${g.gateCode} · ${g.currentStatusName} · ${g.currentOpening}%`"
                      :value="g.gateId"
                      :disabled="!g.operable"
                    />
                  </el-select>
                  <el-input v-model="gateForm.reason" placeholder="调整事由" style="flex:1;min-width:180px" />
                </div>
                <div v-if="selectedGate" class="gate-current">
                  <span>当前 {{ gateRange.current }}%</span>
                  <span>安全区 {{ gateRange.min }} ~ {{ gateRange.max }}%</span>
                  <span>幅度 {{ gateRange.range }}</span>
                  <span :class="gateRange.needApprove ? 'danger-text' : 'ok-text'">
                    {{ gateRange.needApprove ? '超过 30，提交后待审批' : '未超 30，校验通过后执行' }}
                  </span>
                </div>
                <el-slider v-model="gateForm.afterOpening" :min="0" :max="100" :step="0.5" show-input />
                <el-input v-model="gateForm.comment" type="textarea" :rows="2" placeholder="闸门调整处置意见（快照过期后此内容会保留）" />
                <div class="stale-actions">
                  <el-button type="primary" :loading="loading" :disabled="!freshChecked" @click="submitGate">提交闸门调整</el-button>
                  <el-button
                    v-if="order.latestGateOperationNo && order.latestGateOperationStatus === 'PENDING'"
                    type="warning" plain
                    @click="withdraw"
                  >撤回 {{ order.latestGateOperationNo }}</el-button>
                </div>
              </div>

              <div class="decision-card" v-if="order.status === 'HANDLING'">
                <div class="snap-title">提交待复核</div>
                <el-input v-model="reviewForm.comment" type="textarea" :rows="3" placeholder="处置总结；有待审批闸门时不能提交复核" />
                <el-button type="primary" :loading="loading" :disabled="!freshChecked" @click="submitReview">提交复核</el-button>
              </div>

              <div class="decision-card wide" v-if="order.status === 'REVIEW'">
                <div class="snap-title">复核解除（再次核对最新水位和闸门状态）</div>
                <el-input v-model="resolveForm.note" type="textarea" :rows="2" placeholder="复核意见；最新水位仍异常时后端拒绝解除" />
                <el-button type="success" :loading="loading" :disabled="!freshChecked" @click="resolve">确认解除</el-button>
              </div>
            </div>
          </div>
        </template>

        <div class="action-box muted" v-if="!order.open">
          <b v-if="order.status === 'RESOLVED'">已解除：{{ order.resolver }} · {{ order.resolvedAt }}</b>
          <b v-else>已转人工：{{ order.manualReason }}</b>
          <div class="oc-meta">{{ order.resolutionNote }}</div>
        </div>

        <div class="trace-grid">
          <div>
            <div class="snap-title">后续水位如何影响险情</div>
            <div v-for="l in followingLevels" :key="l.id" class="trace-row">
              <span>{{ l.startAt }}</span>
              <b :class="{ 'danger-text': l.abnormal === 1 }">{{ l.measuredLevel }} m</b>
              <span class="oc-meta">{{ l.observer }} · {{ l.abnormal ? l.abnormalReason : '正常' }}</span>
            </div>
          </div>
          <div>
            <div class="snap-title">后续闸门动作追溯</div>
            <div v-for="op in followingOperations" :key="op.id" class="trace-row">
              <span>{{ op.operateTime }}</span>
              <b>{{ op.gateCode }}</b>
              <span>{{ op.beforeOpening }}% → {{ op.afterOpening }}%</span>
              <span>{{ op.statusName }}</span>
              <span v-if="op.emergencyOrderId === order.id" class="linked">本单提出</span>
            </div>
          </div>
        </div>

        <div class="timeline">
          <div class="snap-title">完整处置时间线</div>
          <div v-for="e in timeline" :key="e.id" class="tl-row">
            <span class="tl-time">{{ e.eventTime }}</span>
            <span class="tl-type">{{ e.typeName }}</span>
            <span class="tl-operator">{{ e.operator }}</span>
            <span class="oc-meta">[{{ e.fromStatusName || '—' }} → {{ e.toStatusName || '—' }}]</span>
            <span>{{ e.detail }}</span>
            <span v-if="e.gateOperationNo" class="linked">{{ e.gateCode }} / {{ e.gateOperationNo }}</span>
          </div>
        </div>
      </div>

      <div class="panel detail-panel" v-else>
        <div class="empty-tip">请选择左侧处置单，或从异常水位记录发起处置</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.emergency-grid { display: grid; grid-template-columns: minmax(280px, 360px) minmax(0, 1fr); gap: 12px; align-items: start; }
.panel-title { display:flex; align-items:center; gap:8px; }
.order-card, .level-mini {
  border: 1px solid var(--line); border-radius: 10px; background: #fff;
  padding: 9px 10px; margin: 8px 0; cursor: pointer;
}
.order-card:hover, .order-card.on { border-color: var(--primary); box-shadow: 0 0 0 2px rgba(2,136,209,.12); }
.oc-row, .detail-head, .action-head, .gate-form-row, .stale-actions {
  display:flex; align-items:center; gap:8px; flex-wrap:wrap;
}
.oc-meta { font-size: 11px; color: var(--ink-dim); }
.oc-level { font-size: 18px; font-weight: 800; color: var(--primary-deep); margin: 2px 0; }
.danger-text { color: var(--danger); }
.ok-text { color: var(--safe); }
.due { color:#fff; background:#e53935; border-radius:999px; padding:1px 7px; margin-left:6px; }
.abnormal-title { margin-top: 14px; font-weight: 800; color: var(--ink); }
.level-mini { display:flex; justify-content:space-between; gap:10px; align-items:center; cursor:default; }
.status-pill { border-radius:999px; padding:2px 9px; font-size:11px; font-weight:800; color:#fff; }
.status-pill.big { font-size:12px; padding:3px 12px; }
.tag-danger { background:#e53935; }
.tag-warn { background:#f9a825; }
.tag-info { background:#0288d1; }
.tag-ok { background:#43a047; }
.tag-manual { background:#8d6e63; }
.detail-panel { min-height: 600px; }
.snapshot-grid, .trace-grid { display:grid; grid-template-columns: repeat(3, minmax(0,1fr)); gap:8px; margin:10px 0; }
.trace-grid { grid-template-columns: 1fr 1fr; }
.snap-card { background:#f6fcff; border:1px solid var(--line); border-radius:10px; padding:9px; font-size:12px; }
.snap-title { font-weight:800; margin-bottom:6px; color:var(--ink); }
.snap-level { font-size:22px; font-weight:900; color:var(--primary-deep); }
.gate-snapshot-list { margin:10px 0; }
.gate-snap-row {
  display:flex; gap:10px; align-items:center; flex-wrap:wrap;
  padding:7px 9px; border:1px solid var(--line); border-radius:8px; margin-bottom:6px;
  font-size:11.5px; background:#fff;
}
.gate-snap-row.changed { border-color:#ef9a9a; background:#fff8f8; }
.action-box { border:1px dashed var(--primary); border-radius:10px; background:var(--primary-wash); padding:10px; margin:10px 0; }
.action-box.muted { border-color:var(--line); background:#fafafa; }
.decision-grid { display:grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap:8px; margin-top:9px; }
.decision-card { display:flex; flex-direction:column; gap:7px; border:1px solid var(--line); border-radius:10px; padding:9px; background:#fff; }
.decision-card.wide { grid-column: span 2; }
.gate-current, .warn-line {
  display:flex; gap:10px; flex-wrap:wrap; align-items:center;
  padding:7px 9px; border-radius:8px; font-size:12px;
  background:#fff8e1; border:1px solid #ffe082; color:#8d6e00;
}
.danger-bg { background:#ffebee; border-color:#ef9a9a; color:#b71c1c; }
.stale-box { border:2px solid #ef5350; background:#fff5f5; border-radius:10px; padding:10px; margin:10px 0; }
.change-row { display:grid; grid-template-columns: 1.4fr 1fr 1fr 1.4fr; gap:8px; font-size:11px; padding:6px 0; border-bottom:1px dashed #ffcdd2; }
.change-label { font-weight:800; }
.change-row em { color:#b71c1c; font-style:normal; }
.trace-row, .tl-row {
  display:flex; gap:8px; align-items:center; flex-wrap:wrap;
  padding:6px 0; border-bottom:1px dashed var(--line); font-size:11.5px;
}
.timeline { margin-top:10px; }
.tl-time { color:var(--ink-dim); width:132px; }
.tl-type { font-weight:800; color:var(--primary-deep); width:78px; }
.tl-operator { width:78px; }
.linked { color:#fff; background:#7e57c2; border-radius:999px; padding:1px 7px; font-size:10px; }
@media (max-width: 1100px) {
  .emergency-grid { grid-template-columns: 1fr; }
  .snapshot-grid, .trace-grid { grid-template-columns: 1fr; }
  .decision-grid { grid-template-columns: 1fr; }
  .decision-card.wide { grid-column: span 1; }
}
</style>
