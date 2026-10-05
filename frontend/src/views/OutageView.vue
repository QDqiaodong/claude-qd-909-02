<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const ACTION_NAME = { POSTPONE: '整体顺延', SPLIT: '拆分改期', KEEP: '例外保留' }

const windows = ref([])
const segments = ref([])
const loading = ref(false)
const segmentFilter = ref(null)
const statusFilter = ref('')
const activeWindowId = ref(null)
const items = ref([])
const itemsLoading = ref(false)

// 登记 / 变更对话框
const dialog = ref(false)
const editingId = ref(null)
const form = ref({})

// 避让处理对话框
const resolveDialog = ref(false)
const resolveItem = ref(null)
const resolveAction = ref('POSTPONE')
const resolveForm = ref({ targetDate: '', reason: '', operator: '灌区调度' })
const resolving = ref(false)

const filtered = computed(() => windows.value.filter((w) => !statusFilter.value || w.status === statusFilter.value))
const activeWindow = computed(() => windows.value.find((w) => w.id === activeWindowId.value) || null)
const pendingItems = computed(() => items.value.filter((i) => i.status === 'PENDING'))
const dealtItems = computed(() => items.value.filter((i) => i.status !== 'PENDING'))

function blank() {
  return {
    canalSegmentId: segmentFilter.value || segments.value[0]?.id || null,
    startAt: '',
    endAt: '',
    reason: '',
    scopeType: 'FULL',
    scopeNote: '',
    createdBy: '运维-赵工',
    note: ''
  }
}

function openCreate() {
  editingId.value = null
  form.value = blank()
  dialog.value = true
}

function openEdit(w) {
  editingId.value = w.id
  form.value = {
    canalSegmentId: w.canalSegmentId,
    // 后端给的是「YYYY-MM-DD HH:mm:ss」，日期控件要 ISO「T」分隔
    startAt: (w.startAt || '').replace(' ', 'T'),
    endAt: (w.endAt || '').replace(' ', 'T'),
    reason: w.reason,
    scopeType: w.scopeType,
    scopeNote: w.scopeNote,
    createdBy: w.createdBy,
    note: w.note
  }
  dialog.value = true
}

async function submit() {
  if (!form.value.startAt || !form.value.endAt) {
    ElMessage.warning('请填写停水起止时间')
    return
  }
  loading.value = true
  try {
    const res = editingId.value
      ? await api.outage.update(editingId.value, form.value)
      : await api.outage.create(form.value)
    ElMessage.success(editingId.value ? '窗口已变更，已重新比对重叠计划与配水' : '窗口已登记，重叠项已标为待避让')
    dialog.value = false
    await load()
    if (res && res.id) await pickWindow(res)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function cancelWindow(w) {
  let note = ''
  try {
    const res = await ElMessageBox.prompt(
      `撤销「${w.windowNo}」后，尚未人工确认的待避让项将自动恢复；已顺延 / 已拆分 / 例外保留的结果与历史不会被覆盖。可填写撤销说明：`,
      '撤销停水窗口',
      { inputPlaceholder: '如：与供电所停电计划冲突，取消本次检修', confirmButtonText: '确认撤销', cancelButtonText: '再想想' }
    )
    note = res.value || ''
  } catch {
    return
  }
  loading.value = true
  try {
    await api.outage.cancel(w.id, note)
    ElMessage.success('窗口已撤销，未确认的待避让项已恢复')
    await load()
    if (activeWindowId.value === w.id) await loadItems()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

async function pickWindow(w) {
  activeWindowId.value = w.id
  await loadItems()
}

async function loadItems() {
  if (!activeWindowId.value) return
  itemsLoading.value = true
  try {
    items.value = await api.outage.items(activeWindowId.value) || []
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    itemsLoading.value = false
  }
}

function openResolve(item, action) {
  resolveItem.value = item
  resolveAction.value = action
  resolveForm.value = { targetDate: '', reason: '', operator: '灌区调度' }
  resolveDialog.value = true
}

async function submitResolve() {
  const action = resolveAction.value
  if (action === 'KEEP' && !resolveForm.value.reason.trim()) {
    ElMessage.warning('例外保留必须填写例外依据')
    return
  }
  if (action === 'SPLIT' && resolveItem.value.targetType === 'ALLOT' && !resolveForm.value.targetDate) {
    ElMessage.warning('请选择拆分到的窗口外日期')
    return
  }
  resolving.value = true
  try {
    await api.outage.resolve(resolveItem.value.id, {
      action,
      targetDate: resolveForm.value.targetDate || null,
      reason: resolveForm.value.reason,
      operator: resolveForm.value.operator
    })
    ElMessage.success('避让处理完成：' + ACTION_NAME[action])
    resolveDialog.value = false
    await load()
    await loadItems()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    resolving.value = false
  }
}

function targetLabel(item) {
  if (item.targetType === 'PLAN') {
    return `${item.planCode || '计划#' + item.planId} ${item.planName || ''}`
  }
  return `${item.planCode || ''} 的配水条（${item.origStartDate} · ${item.allotFlow ?? '—'} m³/s）`
}

async function load() {
  loading.value = true
  try {
    const [ws, sg] = await Promise.all([
      api.outage.list(segmentFilter.value || undefined),
      api.canal.segments()
    ])
    windows.value = ws || []
    segments.value = sg || []
    if (activeWindowId.value && !windows.value.some((w) => w.id === activeWindowId.value)) {
      activeWindowId.value = null
      items.value = []
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function pickSegment(id) {
  segmentFilter.value = segmentFilter.value === id ? null : id
  activeWindowId.value = null
  items.value = []
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="view-head">
      <span class="view-title">停水窗口 · 渠段检修停水与避让</span>
      <span class="view-tip">登记 / 变更即扫描重叠计划与配水标为待避让；撤销只恢复未人工确认项，已处理历史不覆盖</span>
    </div>

    <div class="chip-row">
      <span class="chip" :class="{ on: segmentFilter === null }" @click="pickSegment(null)">全部渠段</span>
      <span
        v-for="s in segments"
        :key="s.id"
        class="chip"
        :class="{ on: segmentFilter === s.id }"
        @click="pickSegment(s.id)"
      >{{ s.segmentCode }}</span>
      <span class="chip-sep"></span>
      <span class="chip" :class="{ on: statusFilter === '' }" @click="statusFilter = ''">全部状态</span>
      <span class="chip" :class="{ on: statusFilter === 'ACTIVE' }" @click="statusFilter = 'ACTIVE'">生效中</span>
      <span class="chip" :class="{ on: statusFilter === 'CANCELED' }" @click="statusFilter = 'CANCELED'">已撤销</span>
      <el-button size="small" type="primary" style="margin-left:auto" @click="openCreate">登记停水窗口</el-button>
    </div>

    <div v-if="!filtered.length" class="panel"><div class="empty-tip">暂无停水窗口，点右上角「登记停水窗口」</div></div>

    <div class="ow-grid">
      <div
        v-for="w in filtered"
        :key="w.id"
        class="ow-card"
        :class="{ on: activeWindowId === w.id, canceled: w.status === 'CANCELED' }"
        @click="pickWindow(w)"
      >
        <div class="ow-head">
          <b>{{ w.windowNo }}</b>
          <span class="ow-status" :style="{ background: w.statusColor }">{{ w.statusName }}</span>
          <span v-if="w.pendingCount > 0" class="ow-pending">待避让 {{ w.pendingCount }}</span>
        </div>
        <div class="ow-seg">{{ w.segmentCode }} {{ w.segmentName }}</div>
        <div class="ow-time">
          {{ (w.startAt || '').slice(5, 16) }} ~ {{ (w.endAt || '').slice(5, 16) }}
          <span class="ow-days">跨 {{ w.coverDays }} 天 · {{ w.scopeTypeName }}</span>
        </div>
        <div class="ow-reason">原因：{{ w.reason }}</div>
        <div v-if="w.scopeNote" class="ow-scope">范围：{{ w.scopeNote }}</div>
        <div v-if="w.status === 'CANCELED'" class="ow-cancel">
          撤销于 {{ (w.canceledAt || '').slice(0, 16) }}<span v-if="w.cancelNote"> · {{ w.cancelNote }}</span>
        </div>
        <div class="ow-meta">
          <span>登记：{{ w.createdBy }} · {{ (w.createdAt || '').slice(0, 16) }}</span>
          <span>已处理 {{ w.dealtCount }} · 已恢复 {{ w.restoredCount }}</span>
        </div>
        <div class="ow-actions" @click.stop>
          <el-button size="small" type="primary" plain @click="pickWindow(w)">避让项</el-button>
          <template v-if="w.status === 'ACTIVE'">
            <el-button size="small" plain @click="openEdit(w)">变更</el-button>
            <el-button size="small" type="danger" plain @click="cancelWindow(w)">撤销</el-button>
          </template>
        </div>
      </div>
    </div>

    <div v-if="activeWindow" class="panel">
      <div class="panel-title">
        「{{ activeWindow.windowNo }}」避让项
        <span class="pt-sub">
          {{ activeWindow.segmentCode }} · {{ activeWindow.startDay }} ~ {{ activeWindow.endDay }} · 原因：{{ activeWindow.reason }}
          · 待避让 {{ pendingItems.length }} / 共 {{ items.length }}
        </span>
      </div>
      <div v-if="!items.length && !itemsLoading" class="empty-tip">该窗口当前没有避让项</div>

      <div v-for="item in items" :key="item.id" class="ai-row" :class="{ pending: item.status === 'PENDING' }">
        <div class="ai-target">
          <span class="ai-type" :class="item.targetType === 'PLAN' ? 't-plan' : 't-allot'">{{ item.targetTypeName }}</span>
          <b>{{ targetLabel(item) }}</b>
          <span class="ai-orig">
            原{{ item.targetType === 'PLAN' ? '周期' : '日期' }}：{{ item.origStartDate }}<template v-if="item.origEndDate"> ~ {{ item.origEndDate }}</template>
          </span>
        </div>
        <div class="ai-status">
          <span class="tag-line" :style="{ color: item.statusColor }">
            <i class="dot" :style="{ background: item.statusColor }"></i>{{ item.statusName }}
          </span>
          <span v-if="item.operator" class="ai-op">{{ item.operator }} · {{ (item.resolvedAt || '').slice(0, 16) }}</span>
        </div>
        <div class="ai-dest">
          <template v-if="item.resolutionNote">
            {{ item.resolutionNote }}
            <span v-if="item.splitPlanCode" class="ai-split">→ 新计划 {{ item.splitPlanCode }}</span>
          </template>
          <span v-else class="ai-none">等待调度员处理</span>
        </div>
        <div v-if="item.status === 'PENDING' && activeWindow.status === 'ACTIVE'" class="ai-actions">
          <el-button size="small" type="primary" plain @click="openResolve(item, 'POSTPONE')">整体顺延</el-button>
          <el-button size="small" type="warning" plain @click="openResolve(item, 'SPLIT')">拆分改期</el-button>
          <el-button size="small" plain @click="openResolve(item, 'KEEP')">例外保留</el-button>
        </div>
      </div>
    </div>

    <el-dialog v-model="dialog" :title="editingId ? '变更停水窗口' : '登记停水窗口'" width="560px">
      <el-form label-width="96px">
        <el-form-item label="停水渠段">
          <el-select v-model="form.canalSegmentId" style="width:100%">
            <el-option v-for="s in segments" :key="s.id" :value="s.id" :label="`${s.segmentCode} ${s.segmentName || ''}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="停水开始">
          <el-date-picker v-model="form.startAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="可跨日" style="width:100%" />
        </el-form-item>
        <el-form-item label="停水结束">
          <el-date-picker v-model="form.endAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="结束时间须晚于开始时间" style="width:100%" />
        </el-form-item>
        <el-form-item label="停水原因">
          <el-input v-model="form.reason" placeholder="如：量水堰拆除重建，需断流施工" />
        </el-form-item>
        <el-form-item label="停水范围">
          <el-select v-model="form.scopeType" style="width:160px">
            <el-option value="FULL" label="全段停水" />
            <el-option value="PARTIAL" label="局部停水" />
          </el-select>
        </el-form-item>
        <el-form-item label="范围说明">
          <el-input v-model="form.scopeNote" placeholder="如：桩号 0+800~1+600 全断面" />
        </el-form-item>
        <el-form-item label="登记人"><el-input v-model="form.createdBy" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" placeholder="如：预计 3 天，遇雨顺延另行登记" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="loading" @click="submit">{{ editingId ? '保存变更' : '登记并扫描' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resolveDialog" :title="`避让处理 · ${ACTION_NAME[resolveAction]}`" width="520px">
      <div v-if="resolveItem" class="rv-box">
        <div class="rv-line"><b>对象：</b>{{ targetLabel(resolveItem) }}</div>
        <div class="rv-line">
          <b>原{{ resolveItem.targetType === 'PLAN' ? '周期' : '日期' }}：</b>
          {{ resolveItem.origStartDate }}<template v-if="resolveItem.origEndDate"> ~ {{ resolveItem.origEndDate }}</template>
        </div>
        <div class="rv-line"><b>停水原因：</b>{{ resolveItem.windowReason }}</div>
        <div class="rv-desc">
          <template v-if="resolveAction === 'POSTPONE'">
            {{ resolveItem.targetType === 'PLAN'
              ? '计划周期与其未执行配水整体平移到窗口结束后首日，同步剩余水量与相关用水申请的供水日期。'
              : '配水条移到窗口结束后首日；若超出计划周期将同步顺延计划边界，并同步当日用水申请。' }}
          </template>
          <template v-else-if="resolveAction === 'SPLIT'">
            {{ resolveItem.targetType === 'PLAN'
              ? '计划在窗口边界拆成两段：窗口前保留在原计划，窗口起及之后的后段平移生成新计划（编号加 -B），同步配水与用水申请。'
              : '把这条配水改到指定的窗口外日期；若超出计划周期将同步顺延计划边界，并同步当日用水申请。' }}
          </template>
          <template v-else>
            保留原安排不动，必须填写例外依据；该依据会随窗口与计划甘特一并留痕展示。
          </template>
        </div>
        <el-form label-width="96px" style="margin-top:10px">
          <el-form-item v-if="resolveAction === 'SPLIT' && resolveItem.targetType === 'ALLOT'" label="窗口外日期">
            <el-date-picker v-model="resolveForm.targetDate" type="date" value-format="YYYY-MM-DD" placeholder="选择窗口外日期" style="width:100%" />
          </el-form-item>
          <el-form-item :label="resolveAction === 'KEEP' ? '例外依据' : '补充说明'">
            <el-input
              v-model="resolveForm.reason"
              type="textarea"
              :rows="2"
              :placeholder="resolveAction === 'KEEP' ? '必填：如抽雄期补水临界，批准低压过水' : '可选'"
            />
          </el-form-item>
          <el-form-item label="处理人"><el-input v-model="resolveForm.operator" /></el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="resolveDialog = false">取消</el-button>
        <el-button type="primary" :loading="resolving" @click="submitResolve">确认{{ ACTION_NAME[resolveAction] }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.chip-sep { width: 1px; height: 18px; background: var(--line-strong); margin: 0 4px; }

.ow-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 12px; }
.ow-card {
  background: #fff; border: 1px solid var(--line-strong); border-left: 4px solid #e53935;
  border-radius: 11px; padding: 10px 12px; cursor: pointer;
  box-shadow: 0 6px 14px rgba(1, 87, 155, 0.08); transition: transform 0.12s ease;
}
.ow-card:hover { transform: translateY(-1px); }
.ow-card.on { outline: 2px solid var(--primary); }
.ow-card.canceled { border-left-color: #90a4ae; opacity: 0.82; }
.ow-head { display: flex; align-items: center; gap: 8px; }
.ow-head b { font-size: 13px; color: var(--ink); }
.ow-status { margin-left: auto; padding: 1px 8px; border-radius: 999px; font-size: 10.5px; font-weight: 700; color: #fff; }
.ow-pending { padding: 1px 8px; border-radius: 999px; font-size: 10.5px; font-weight: 700; color: #fff; background: #f9a825; }
.ow-seg { font-size: 12px; color: var(--ink-soft); margin-top: 4px; }
.ow-time { font-size: 12px; color: var(--ink); margin-top: 4px; font-weight: 600; }
.ow-days { font-size: 10.5px; color: var(--ink-dim); font-weight: 400; margin-left: 6px; }
.ow-reason { font-size: 11.5px; color: #b71c1c; margin-top: 4px; }
.ow-scope { font-size: 11px; color: var(--ink-dim); margin-top: 2px; }
.ow-cancel { font-size: 11px; color: #8d6e63; margin-top: 4px; }
.ow-meta { display: flex; justify-content: space-between; gap: 8px; flex-wrap: wrap; font-size: 10.5px; color: var(--ink-dim); margin-top: 6px; }
.ow-actions { margin-top: 8px; display: flex; gap: 6px; }

.pt-sub { font-size: 11px; color: var(--ink-dim); font-weight: 400; margin-left: 8px; }

.ai-row {
  display: grid; grid-template-columns: minmax(220px, 1.2fr) minmax(120px, 0.6fr) minmax(220px, 1.4fr) auto;
  gap: 10px; align-items: center; padding: 9px 10px; border-radius: 9px;
  border: 1px solid var(--line); margin-bottom: 8px; background: #fbfdff;
}
.ai-row.pending { border-color: #ffe082; background: #fffdf3; }
.ai-target b { font-size: 12.5px; color: var(--ink); display: block; margin: 2px 0; }
.ai-type {
  display: inline-block; padding: 0 6px; border-radius: 5px; font-size: 10px; font-weight: 700; color: #fff;
}
.ai-type.t-plan { background: #0288d1; }
.ai-type.t-allot { background: #26c6da; }
.ai-orig { font-size: 10.5px; color: var(--ink-dim); }
.ai-status { display: flex; flex-direction: column; gap: 2px; }
.ai-op { font-size: 10px; color: var(--ink-dim); }
.ai-dest { font-size: 11.5px; color: var(--ink-soft); line-height: 1.5; }
.ai-none { color: var(--ink-dim); }
.ai-split { color: #5e35b1; font-weight: 700; margin-left: 4px; }
.ai-actions { display: flex; gap: 6px; flex-wrap: wrap; }

.rv-box { font-size: 12.5px; color: var(--ink-soft); }
.rv-line { margin-bottom: 4px; }
.rv-desc {
  margin-top: 8px; padding: 8px 10px; border-radius: 8px; font-size: 11.5px; line-height: 1.6;
  color: #01579b; background: var(--primary-wash); border: 1px solid var(--line);
}

@media (max-width: 1100px) {
  .ai-row { grid-template-columns: 1fr; }
}
</style>
