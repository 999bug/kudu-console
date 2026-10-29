const BASE = ''

/** 当前选中的平台 id：随每个请求通过 X-Kudu-Cluster 头发给后端 */
let activeCluster = ''

export function setActiveCluster(id) {
  activeCluster = id || ''
}

export function getActiveCluster() {
  return activeCluster
}

export class ApiError extends Error {
  constructor(code, message, status) {
    super(message)
    this.code = code
    this.status = status
  }
}

async function request(method, path, { params, body } = {}) {
  let url = BASE + path
  if (params) {
    const search = new URLSearchParams()
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') {
        search.append(key, value)
      }
    })
    const qs = search.toString()
    if (qs) url += '?' + qs
  }
  const options = { method, headers: {} }
  if (activeCluster) {
    options.headers['X-Kudu-Cluster'] = activeCluster
  }
  if (body !== undefined) {
    options.headers['Content-Type'] = 'application/json'
    options.body = JSON.stringify(body)
  }
  let response
  try {
    response = await fetch(url, options)
  } catch (e) {
    throw new ApiError('NETWORK', '无法连接到后端服务', 0)
  }
  const text = await response.text()
  let data = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch (e) {
      data = null
    }
  }
  if (!response.ok) {
    const code = data?.code || 'HTTP_' + response.status
    const message = data?.message || text || response.statusText
    throw new ApiError(code, message, response.status)
  }
  return data
}

export const api = {
  // 平台
  listClusters: () => request('GET', '/api/clusters'),
  createCluster: (body) => request('POST', '/api/clusters', { body }),
  updateCluster: (id, body) => request('PUT', '/api/clusters/' + id, { body }),
  deleteCluster: (id) => request('DELETE', '/api/clusters/' + id),
  activateCluster: (id) => request('POST', `/api/clusters/${id}/activate`),
  testCluster: (masters) => request('POST', '/api/clusters/test', { body: { masters } }),
  limits: () => request('GET', '/api/meta/limits'),

  clusterStatus: () => request('GET', '/api/cluster/status'),
  listTables: () => request('GET', '/api/tables'),
  previewDropPrefix: (prefix) => request('GET', '/api/tables/prefix', { params: { prefix } }),
  tableDetail: (table) => request('GET', '/api/table', { params: { table } }),
  options: () => request('GET', '/api/meta/options'),

  query: (table, body) => request('POST', '/api/table/query', { params: { table }, body }),
  mutate: (table, body) => request('POST', '/api/table/rows', { params: { table }, body }),

  createTable: (body) => request('POST', '/api/table/create', { body }),
  dropTable: (table, confirm) => request('POST', '/api/table/drop', { params: { table }, body: { confirm } }),
  renameTable: (table, newName) =>
    request('POST', '/api/table/rename', { params: { table }, body: { newName } }),
  addColumn: (table, column) => request('POST', '/api/table/columns/add', { params: { table }, body: column }),
  dropColumn: (table, column) =>
    request('POST', '/api/table/columns/drop', { params: { table }, body: { column } }),
  setReplicas: (table, replicas) =>
    request('POST', '/api/table/replicas', { params: { table }, body: { replicas } }),

  truncate: (table, confirm) =>
    request('POST', '/api/jobs/truncate', { params: { table }, body: { confirm, acknowledge: true } }),
  dropDb: (db, confirm) =>
    request('POST', '/api/jobs/drop-db', { params: { db }, body: { confirm, acknowledge: true } }),
  dropPrefix: (prefix, confirm) =>
    request('POST', '/api/jobs/drop-prefix', { params: { prefix }, body: { confirm, acknowledge: true } }),
  jobs: () => request('GET', '/api/jobs'),
  job: (id) => request('GET', '/api/jobs/' + id),
  cancelJob: (id) => request('POST', `/api/jobs/${id}/cancel`),

  // 备份测试
  testMeta: () => request('GET', '/api/test/meta'),
  runTestOps: (tables, actions) => request('POST', '/api/test/run', { body: { tables, actions } }),
  listTestSchedules: () => request('GET', '/api/test/schedules'),
  createTestSchedule: (body) => request('POST', '/api/test/schedules', { body }),
  updateTestSchedule: (id, body) => request('PUT', '/api/test/schedules/' + id, { body }),
  deleteTestSchedule: (id) => request('DELETE', '/api/test/schedules/' + id),
  runTestSchedule: (id) => request('POST', `/api/test/schedules/${id}/run`),
  toggleTestSchedule: (id, enabled) =>
    request('POST', `/api/test/schedules/${id}/toggle`, { body: { enabled } }),
  testScheduleRuns: (id) => request('GET', `/api/test/schedules/${id}/runs`)
}
