<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const STATUS_NAME = { PENDING: '待审', APPROVED: '已批', SUPPLIED: '已供水', REJECTED: '已驳回' }
const STATUS_COLOR = { PENDING: '#f9a825', APPROVED: '#0288d1', SUPPLIED: '#43a047', REJECTED: '#e53935' }

const requests = ref([])
const users = ref([])
const segments = ref([])
const loading = ref(false)
const segmentFilter = ref(null)
const dialog = ref(false)
const holdDialog = ref(false)
const holdRows = ref([])
const form = ref({
  requestNo: '',
  waterUserId: null,
  canalSegmentId: null,
  allotDate: '',
  requestVolume: 1,
  cropType: 'RICE',
  applyAreaMu: 10,
  urgency: 'NORMAL',
  note: ''
})

// 三列审批流：待审 → 已批 → 已供水，另加一列已驳回（终态）
const columns = computed(() => ['PENDING', 'APPROVED', 'SUPPLIED', 'REJECTED'])

function inColumn(status) {
  return requests.value.filter((r) => r.status === status)
}

function today() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function nextRequestNo() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `WR-${d.getFullYear()}-${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}

function openCreate() {
  form.value = {
    requestNo: nextRequestNo(),
    waterUserId: users.value[0]?.id || null,
    canalSegmentId: segmentFilter.value || segments.value[0]?.id || null,
    allotDate: today(),
    requestVolume: 1,
    cropType: 'RICE',
    applyAreaMu: 10,
    urgency: 'NORMAL',
    note: ''
  }
  dialog.value = true
}

async function submitCreate() {
  loading.value = true
  try {
    await api.user.createRequest(form.value)
    ElMessage.success('用水申请已提交')
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function advance(row) {
  loading.value = true
  try {
    await api.user.advance(row.id, '灌区调度')
    ElMessage.success('申请已推进到「' + STATUS_NAME[nextOf(row.status)] + '」')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function nextOf(status) {
  if (status === 'PENDING') return 'APPROVED'
  if (status === 'APPROVED') return 'SUPPLIED'
  return status
}

async function reject(row) {
  let reason = ''
  try {
    const res = await ElMessageBox.prompt('请填写驳回理由（必填）', '驳回用水申请', {
      inputPlaceholder: '如：该渠段当日可配水量不足',
      inputValidator: (v) => (v && String(v).trim().length > 0) || '驳回理由不能为空'
    })
    reason = res.value
  } catch {
    return
  }
  loading.value = true
  try {
    await api.user.reject(row.id, '灌区调度', reason)
    ElMessage.success('申请已驳回')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function backToPending(row) {
  loading.value = true
  try {
    await api.user.backToPending(row.id)
    ElMessage.success('申请已退回待审')
    await load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function showHolds(row) {
  loading.value = true
  try {
    holdRows.value = await api.user.holds(row.id)
    holdDialog.value = true
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function holdStatusName(status) {
  return { HELD: '已预占', CONSUMED: '已供水入账', RELEASED: '已释放' }[status] || status
}

async function load() {
  loading.value = true
  try {
    const [rs, us, sg] = await Promise.all([
      api.user.requests(segmentFilter.value || undefined),
      api.user.vo(),
      api.canal.segments()
    ])
    requests.value = rs || []
    users.value = us || []
    segments.value = sg || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function pickSegment(id) {
  segmentFilter.value = segmentFilter.value === id ? null : id
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">用水申请 · 三列审批流</span>
      <span class="view-tip">待审 / 驳回不占额度；批准即在「渠段 + 供水日」原子预占，退回待审释放；终态审计不可重复释放</span>
    </div>

    <div class="chip-row">
      <span class="chip" :class="{ on: segmentFilter === null }" @click="segmentFilter = null; load()">全部渠段</span>
      <span
        v-for="s in segments"
        :key="s.id"
        class="chip"
        :class="{ on: segmentFilter === s.id }"
        @click="pickSegment(s.id)"
      >{{ s.segmentCode }}</span>
      <el-button size="small" type="primary" style="margin-left:auto" @click="openCreate">新增用水申请</el-button>
    </div>

    <div class="board">
      <div v-for="col in columns" :key="col" class="col" :style="{ borderTopColor: STATUS_COLOR[col] }">
        <div class="col-head">
          <span class="col-title" :style="{ color: STATUS_COLOR[col] }">{{ STATUS_NAME[col] }}</span>
          <span class="col-count">{{ inColumn(col).length }}</span>
        </div>
        <div v-if="!inColumn(col).length" class="col-empty">暂无</div>
        <div v-for="r in inColumn(col)" :key="r.id" class="req-card">
          <div class="rc-head">
            <b>{{ r.requestNo }}</b>
            <span class="tag-line" :style="{ color: r.cropTypeColor }">
              <i class="dot" :style="{ background: r.cropTypeColor }"></i>{{ r.cropTypeName }}
            </span>
          </div>
          <div class="rc-user">{{ r.userName }} · {{ r.village }}</div>
          <div class="rc-meta">{{ r.segmentCode }} · {{ r.allotDate }} · {{ r.applyAreaMu }} 亩</div>
          <div class="rc-vol">
            <b>{{ r.requestVolume }}</b> 万m³
            <span v-if="r.urgency === 'URGENT'" class="urgent">抗旱应急</span>
          </div>
          <div class="quota">
            <div class="quota-bar" :class="{ full: r.quotaRate >= 1 }">
              <i :style="{ width: Math.min(100, (r.quotaRate || 0) * 100).toFixed(1) + '%' }"></i>
            </div>
            <span class="quota-text">
              占当日容量 {{ ((r.quotaRate || 0) * 100).toFixed(0) }}%（已扣已排配水与已批申请，剩 {{ r.remainVolume }} 万m³）
            </span>
            <span v-if="r.quotaHoldStatus" class="quota-audit">
              {{ r.quotaHoldStatus === 'HELD' ? `额度已预占 · ${r.quotaHeldBy || '—'} · ${r.quotaHeldAt || '—'}` : '' }}
              <template v-if="r.quotaHoldStatus === 'CONSUMED'">预占已随供水入账 · {{ r.quotaConsumedAt || '—' }}</template>
              <template v-if="r.quotaHoldStatus === 'RELEASED'">预占已释放 · {{ r.quotaReleaseReason || '—' }}</template>
            </span>
            <el-button
              v-if="r.quotaHoldId"
              class="quota-history-btn"
              link
              type="primary"
              size="small"
              @click="showHolds(r)"
            >额度流水</el-button>
          </div>
          <div v-if="r.rejectReason" class="rc-reject">驳回理由：{{ r.rejectReason }}</div>
          <div class="rc-actions">
            <template v-if="r.status === 'PENDING'">
              <el-button size="small" type="primary" plain :loading="loading" @click="advance(r)">批准</el-button>
              <el-button size="small" type="danger" plain :loading="loading" @click="reject(r)">驳回</el-button>
            </template>
            <template v-else-if="r.status === 'APPROVED'">
              <el-button size="small" type="primary" plain :loading="loading" @click="advance(r)">已供水</el-button>
              <el-button size="small" plain :loading="loading" @click="backToPending(r)">退回待审</el-button>
            </template>
            <span v-else class="terminal">终态 · {{ r.approveTime || r.supplyTime || '—' }}</span>
          </div>
        </div>
      </div>
    </div>

    <el-dialog v-model="dialog" title="新增用水申请" width="520px">
      <el-form label-width="96px">
        <el-form-item label="申请单号"><el-input v-model="form.requestNo" /></el-form-item>
        <el-form-item label="用水户">
          <el-select v-model="form.waterUserId" style="width:100%">
            <el-option v-for="u in users" :key="u.id" :value="u.id" :label="`${u.userName}（${u.userCode}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="取水渠段">
          <el-select v-model="form.canalSegmentId" style="width:100%">
            <el-option v-for="s in segments" :key="s.id" :value="s.id" :label="`${s.segmentCode} ${s.segmentName || ''}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="用水日期">
          <el-input v-model="form.allotDate" placeholder="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="申请水量">
          <el-input-number v-model="form.requestVolume" :min="0.01" :step="0.5" />
          <span style="margin-left:8px;color:#8aa6b8">万m³</span>
        </el-form-item>
        <el-form-item label="作物类型">
          <el-select v-model="form.cropType" style="width:160px">
            <el-option value="RICE" label="水稻" />
            <el-option value="WHEAT" label="冬小麦" />
            <el-option value="MAIZE" label="玉米" />
            <el-option value="COTTON" label="棉花" />
            <el-option value="VEGETABLE" label="蔬菜" />
          </el-select>
        </el-form-item>
        <el-form-item label="申请亩数"><el-input-number v-model="form.applyAreaMu" :min="1" /></el-form-item>
        <el-form-item label="紧急度">
          <el-select v-model="form.urgency" style="width:160px">
            <el-option value="NORMAL" label="常规" />
            <el-option value="URGENT" label="抗旱应急" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="submitCreate">提交申请</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="holdDialog" title="额度预占审计流水" width="720px">
      <el-table :data="holdRows" size="small" border>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="状态" width="110">
          <template #default="scope">{{ holdStatusName(scope.row.status) }}</template>
        </el-table-column>
        <el-table-column prop="holdVolume" label="水量（万m³）" width="120" />
        <el-table-column prop="heldBy" label="预占人" width="100" />
        <el-table-column prop="heldAt" label="预占时间" width="170" />
        <el-table-column prop="releasedBy" label="释放人" width="100" />
        <el-table-column prop="releasedAt" label="释放时间" width="170" />
        <el-table-column prop="releaseReason" label="释放原因" min-width="180" />
        <el-table-column prop="consumedAt" label="入账时间" width="170" />
      </el-table>
      <template #footer>
        <el-button @click="holdDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.board { display: grid; grid-template-columns: repeat(4, minmax(240px, 1fr)); gap: 12px; align-items: start; }
.col {
  min-height: 260px; padding: 10px; border-radius: 12px;
  background: rgba(255, 255, 255, 0.62); border: 1px solid var(--line); border-top: 4px solid var(--primary);
}
.col-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.col-title { font-size: 13px; font-weight: 800; }
.col-count {
  min-width: 22px; padding: 1px 7px; border-radius: 999px;
  font-size: 11px; font-weight: 800; color: #fff; background: var(--primary);
}
.col-empty { padding: 18px; text-align: center; color: var(--ink-dim); font-size: 12px; }

.req-card {
  background: #fff; border: 1px solid var(--line-strong); border-radius: 11px;
  padding: 10px 11px; margin-bottom: 10px; box-shadow: 0 6px 14px rgba(1, 87, 155, 0.08);
}
.rc-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.rc-user { font-size: 12.5px; color: var(--ink); margin-top: 4px; }
.rc-meta { font-size: 11px; color: var(--ink-dim); margin-top: 2px; }
.rc-vol { margin-top: 6px; font-size: 11.5px; color: var(--ink-soft); }
.rc-vol b { font-size: 17px; color: var(--primary-deep); }
.urgent {
  margin-left: 6px; padding: 1px 7px; border-radius: 6px; font-size: 10.5px; font-weight: 700;
  color: #fff; background: #e53935;
}
.quota { margin-top: 6px; }
.quota-bar { height: 5px; border-radius: 4px; background: #e3f2fd; overflow: hidden; }
.quota-bar i { display: block; height: 100%; border-radius: 4px; background: linear-gradient(90deg, #4fc3f7, var(--primary)); }
.quota-bar.full i { background: linear-gradient(90deg, #f9a825, #e53935); }
.quota-text { display: block; font-size: 10.5px; color: var(--ink-dim); }
.quota-audit { display: block; margin-top: 2px; font-size: 10px; color: #01579b; }
.quota-history-btn { height: 18px; margin-top: 2px; padding: 0; font-size: 10px; }
.rc-reject {
  margin-top: 6px; padding: 5px 8px; border-radius: 7px; font-size: 11px;
  color: #b71c1c; background: #ffebee; border: 1px solid #ffcdd2;
}
.rc-actions { margin-top: 9px; display: flex; gap: 6px; align-items: center; }
.terminal { font-size: 11px; color: var(--ink-dim); }
</style>
