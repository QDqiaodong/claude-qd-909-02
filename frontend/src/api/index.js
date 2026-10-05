import axios from 'axios'

// 走 nginx 反代：/api/* → backend:8080
const http = axios.create({ baseURL: '/api', timeout: 20000 })

// 后端统一返回 {ok, message, data}；HTTP 400 的业务异常也要把中文提示带出来
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const body = error?.response?.data
    const wrapped = new Error(body?.message || error?.message || '请求失败')
    wrapped.status = error?.response?.status
    wrapped.data = body?.data
    return Promise.reject(wrapped)
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

// ---------------- 模块一 · 渠系与渠段 ----------------
const canal = {
  list: () => call(http.get('/canal/list')),
  topology: () => call(http.get('/canal/topology')),
  segments: (canalId) => call(http.get('/canal/segments', { params: { canalId } })),
  segment: (id) => call(http.get(`/canal/segment/${id}`)),
  segmentDetail: (id) => call(http.get(`/canal/segment/${id}/detail`)),
  get: (id) => call(http.get(`/canal/${id}`)),
  create: (data) => call(http.post('/canal', data)),
  update: (id, data) => call(http.put(`/canal/${id}`, data)),
  status: (id, status) => call(http.post(`/canal/${id}/status?status=${encodeURIComponent(status)}`)),
  remove: (id) => call(http.delete(`/canal/${id}`)),
  createSegment: (data) => call(http.post('/canal/segment', data)),
  updateSegment: (id, data) => call(http.put(`/canal/segment/${id}`, data)),
  segmentStatus: (id, status) => call(http.post(`/canal/segment/${id}/status?status=${encodeURIComponent(status)}`)),
  removeSegment: (id) => call(http.delete(`/canal/segment/${id}`))
}

// ---------------- 模块二 · 闸门与开度操作 ----------------
const gate = {
  list: (segmentId) => call(http.get('/gate/list', { params: { segmentId } })),
  panel: (segmentId) => call(http.get('/gate/panel', { params: { segmentId } })),
  options: () => call(http.get('/gate/options')),
  operations: (gateId) => call(http.get('/gate/operations', { params: { gateId } })),
  get: (id) => call(http.get(`/gate/${id}`)),
  create: (data) => call(http.post('/gate', data)),
  update: (id, data) => call(http.put(`/gate/${id}`, data)),
  status: (id, status) => call(http.post(`/gate/${id}/status?status=${encodeURIComponent(status)}`)),
  remove: (id) => call(http.delete(`/gate/${id}`)),
  // confirmed=true 表示前端已做「超安全幅度二次确认」
  adjust: (data, confirmed) => call(http.post(`/gate/operation?confirmed=${confirmed ? 'true' : 'false'}`, data)),
  approve: (id, approver) => call(http.post(`/gate/operation/${id}/approve`, null, { params: { approver } })),
  reject: (id, approver, reason) => call(http.post(`/gate/operation/${id}/reject`, null, { params: { approver, reason } })),
  operation: (id) => call(http.get(`/gate/operation/${id}`))
}

// ---------------- 模块三 · 灌溉计划与配水 ----------------
const plan = {
  list: (segmentId) => call(http.get('/plan/list', { params: { segmentId } })),
  vo: (segmentId) => call(http.get('/plan/vo', { params: { segmentId } })),
  gantt: (segmentId) => call(http.get('/plan/gantt', { params: { segmentId } })),
  remain: (segmentId, date) => call(http.get('/plan/remain', { params: { segmentId, date } })),
  allotments: (planId, segmentId) => call(http.get('/plan/allotments', { params: { planId, segmentId } })),
  allotment: (id) => call(http.get(`/plan/allotment/${id}`)),
  get: (id) => call(http.get(`/plan/${id}`)),
  create: (data) => call(http.post('/plan', data)),
  update: (id, data) => call(http.put(`/plan/${id}`, data)),
  advance: (id, approver) => call(http.post(`/plan/${id}/advance`, null, { params: { approver } })),
  backToDraft: (id) => call(http.post(`/plan/${id}/draft`)),
  remove: (id) => call(http.delete(`/plan/${id}`)),
  createAllotment: (data) => call(http.post('/plan/allotment', data)),
  updateAllotment: (id, data) => call(http.put(`/plan/allotment/${id}`, data)),
  moveAllotment: (id, date) => call(http.post(`/plan/allotment/${id}/move?date=${encodeURIComponent(date)}`)),
  removeAllotment: (id) => call(http.delete(`/plan/allotment/${id}`))
}

// ---------------- 模块三补 · 渠段检修停水窗口 ----------------
const outage = {
  list: (segmentId) => call(http.get('/outage/list', { params: { segmentId } })),
  pending: () => call(http.get('/outage/pending')),
  items: (id) => call(http.get(`/outage/${id}/items`)),
  create: (data) => call(http.post('/outage', data)),
  update: (id, data) => call(http.put(`/outage/${id}`, data)),
  cancel: (id, note) => call(http.post(`/outage/${id}/cancel`, null, { params: { note } })),
  resolve: (itemId, data) => call(http.post(`/outage/item/${itemId}/resolve`, data))
}

// ---------------- 模块四 · 水位量测 ----------------
const level = {
  list: (segmentId) => call(http.get('/level/list', { params: { segmentId } })),
  vo: (segmentId) => call(http.get('/level/vo', { params: { segmentId } })),
  series: (segmentId) => call(http.get('/level/series', { params: { segmentId } })),
  derived: () => call(http.get('/level/derived')),
  get: (id) => call(http.get(`/level/${id}`)),
  create: (data) => call(http.post('/level', data)),
  update: (id, data) => call(http.put(`/level/${id}`, data)),
  refresh: (id) => call(http.post(`/level/${id}/refresh`)),
  remove: (id) => call(http.delete(`/level/${id}`))
}

// ---------------- 异常水位险情处置台 ----------------
const emergency = {
  list: (segmentId) => call(http.get('/emergency/list', { params: { segmentId } })),
  detail: (id) => call(http.get(`/emergency/${id}`)),
  create: (data) => call(http.post('/emergency', data)),
  take: (id, data) => call(http.post(`/emergency/${id}/take`, data)),
  refresh: (id, data) => call(http.post(`/emergency/${id}/refresh`, data)),
  observe: (id, data) => call(http.post(`/emergency/${id}/observe`, data)),
  gate: (id, data) => call(http.post(`/emergency/${id}/gate`, data)),
  withdraw: (id, data) => call(http.post(`/emergency/${id}/withdraw`, data)),
  review: (id, data) => call(http.post(`/emergency/${id}/review`, data)),
  resolve: (id, data) => call(http.post(`/emergency/${id}/resolve`, data)),
  manual: (id, data) => call(http.post(`/emergency/${id}/manual`, data))
}

// ---------------- 模块五 · 用水户与用水申请 ----------------
const user = {
  list: (segmentId) => call(http.get('/user/list', { params: { segmentId } })),
  vo: (segmentId) => call(http.get('/user/vo', { params: { segmentId } })),
  requests: (segmentId) => call(http.get('/user/requests', { params: { segmentId } })),
  columns: () => call(http.get('/user/requests/columns')),
  remain: (segmentId, date) => call(http.get('/user/remain', { params: { segmentId, date } })),
  get: (id) => call(http.get(`/user/${id}`)),
  create: (data) => call(http.post('/user', data)),
  update: (id, data) => call(http.put(`/user/${id}`, data)),
  remove: (id) => call(http.delete(`/user/${id}`)),
  createRequest: (data) => call(http.post('/user/request', data)),
  request: (id) => call(http.get(`/user/request/${id}`)),
  advance: (id, approver) => call(http.post(`/user/request/${id}/advance`, null, { params: { approver } })),
  reject: (id, approver, reason) => call(http.post(`/user/request/${id}/reject`, null, { params: { approver, reason } })),
  backToPending: (id) => call(http.post(`/user/request/${id}/pending`)),
  removeRequest: (id) => call(http.delete(`/user/request/${id}`))
}

// ---------------- 统计与字典 ----------------
const stats = {
  overview: () => call(http.get('/stats/overview')),
  canalGrade: () => call(http.get('/stats/canal-grade')),
  gateType: () => call(http.get('/stats/gate-type')),
  planStatus: () => call(http.get('/stats/plan-status')),
  requestStatus: () => call(http.get('/stats/request-status')),
  segmentStatus: () => call(http.get('/stats/segment-status')),
  dict: () => call(http.get('/stats/dict'))
}

export default { http, canal, gate, plan, outage, level, emergency, user, stats }
