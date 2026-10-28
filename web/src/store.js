import { reactive } from 'vue'
import { api, setActiveCluster } from './api'

const STORAGE_KEY = 'kudu.console.cluster'

export const toasts = reactive([])

let seq = 0
export function toast(message, type = 'ok', timeout = 4000) {
  const id = ++seq
  toasts.push({ id, message: String(message ?? ''), type })
  if (timeout > 0) {
    setTimeout(() => removeToast(id), timeout)
  }
  return id
}

export function removeToast(id) {
  const index = toasts.findIndex((t) => t.id === id)
  if (index >= 0) toasts.splice(index, 1)
}

export function toastError(error) {
  toast(error?.message || String(error), 'error', 8000)
}

export const store = reactive({
  clusters: [],
  activeId: '',
  cluster: null,
  loading: false,
  error: null,
  clustersLoaded: false
})

export function activeCluster() {
  return store.clusters.find((c) => c.id === store.activeId) || null
}

/** 读取平台清单，恢复上次选择的平台（不存在则回落到服务端激活平台）。 */
export async function loadClusters() {
  const data = await api.listClusters()
  store.clusters = data.clusters || []
  if (!store.clusters.length) {
    store.activeId = ''
    setActiveCluster('')
    return
  }
  const saved = localStorage.getItem(STORAGE_KEY)
  const exists = store.clusters.some((c) => c.id === saved)
  const id = exists ? saved : data.activeId
  store.activeId = id
  setActiveCluster(id)
  localStorage.setItem(STORAGE_KEY, id)
  if (id && id !== data.activeId) {
    try {
      await api.activateCluster(id)
    } catch (e) {
      // 服务端激活失败不影响浏览：每个请求都带 header
    }
  }
  store.clustersLoaded = true
}

/** 切换平台：写 localStorage + 同步服务端激活项 + 刷新状态。 */
export async function switchCluster(id) {
  if (!id || id === store.activeId) return
  store.activeId = id
  setActiveCluster(id)
  localStorage.setItem(STORAGE_KEY, id)
  store.cluster = null
  try {
    await api.activateCluster(id)
  } catch (e) {
    // 忽略：请求仍然带 header
  }
  await refreshCluster()
}

export async function refreshCluster() {
  if (!store.activeId) {
    store.cluster = null
    return
  }
  store.loading = true
  try {
    store.cluster = await api.clusterStatus()
    store.error = null
  } catch (e) {
    store.error = e.message
    store.cluster = null
  } finally {
    store.loading = false
  }
}
