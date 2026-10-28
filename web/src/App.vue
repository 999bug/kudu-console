<script setup>
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  store, toasts, removeToast, refreshCluster, loadClusters, switchCluster,
  toast, activeCluster
} from './store'

const route = useRoute()
const router = useRouter()

const navItems = [
  { path: '/cluster', label: '集群状态' },
  { path: '/tables', label: '表管理' },
  { path: '/jobs', label: '任务中心' },
  { path: '/clusters', label: 'Kudu 平台' }
]

const current = computed(() => activeCluster())
const connected = computed(() => store.cluster?.connected === true)
const dotClass = computed(() => {
  if (!store.activeId) return ''
  if (store.error || connected.value === false) return 'bad'
  if (!store.cluster) return 'warn'
  return 'ok'
})
const connectionLabel = computed(() => {
  if (!store.activeId) return '未配置平台'
  if (store.error || connected.value === false) return '连接异常'
  if (!store.cluster) return '检测中'
  return '已连接'
})

const pageTitle = computed(() => {
  if (route.path === '/table') return '表详情'
  return route.meta?.title || 'Kudu 控制台'
})

const subtitle = computed(() => {
  if (route.path === '/table') return route.query.name || ''
  return current.value?.masters || ''
})

async function onSwitch(event) {
  const id = event.target.value
  try {
    await switchCluster(id)
    toast('已切换到 ' + (store.clusters.find((c) => c.id === id)?.name || id))
    if (route.path === '/table' || route.path === '/jobs') {
      router.push('/tables')
    }
  } catch (e) {
    toast(e.message, 'error')
  }
}

let timer = null
onMounted(async () => {
  try {
    await loadClusters()
  } catch (e) {
    store.error = e.message
  }
  await refreshCluster()
  timer = setInterval(() => {
    if (!document.hidden) refreshCluster()
  }, 30000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="shell">
    <aside class="side">
      <div class="brand">
        <div class="brand-name">Kudu 控制台</div>
        <div class="brand-sub">multi-platform console</div>
      </div>

      <div class="cluster-box">
        <label class="cluster-label">当前平台</label>
        <select class="select cluster-select" :value="store.activeId" @change="onSwitch">
          <option v-if="!store.clusters.length" value="">未配置</option>
          <option v-for="item in store.clusters" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
        <div class="cluster-masters mono" :title="current?.masters || ''">{{ current?.masters || '—' }}</div>
      </div>

      <nav class="nav">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          :class="{ active: route.path === item.path || (item.path === '/tables' && route.path === '/table') }"
        >
          <span class="nav-dot"></span>{{ item.label }}
        </router-link>
      </nav>

      <div class="side-foot">
        <div class="status">
          <span class="status-dot" :class="dotClass"></span>{{ connectionLabel }}
        </div>
        <div class="masters">
          <div v-if="store.clusters.length">{{ store.clusters.length }} 个平台</div>
          <div v-else>请先在「Kudu 平台」添加</div>
        </div>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div>
          <div class="topbar-title">{{ pageTitle }}</div>
          <div class="topbar-sub" v-if="subtitle">{{ subtitle }}</div>
        </div>
        <div class="topbar-right">
          <span class="tag plain" v-if="store.cluster?.kuduVersion">{{ store.cluster.kuduVersion }}</span>
          <button class="btn sm" @click="refreshCluster" :disabled="store.loading || !store.activeId">
            <span v-if="store.loading" class="spinner"></span>刷新状态
          </button>
        </div>
      </header>
      <main class="content">
        <div v-if="store.error" class="banner error">
          平台「{{ current?.name }}」连接失败：{{ store.error }}
          <router-link to="/clusters" style="margin-left: 8px">检查配置</router-link>
        </div>
        <router-view :key="store.activeId" />
      </main>
    </div>

    <div class="toasts">
      <div v-for="item in toasts" :key="item.id" class="toast" :class="item.type" @click="removeToast(item.id)">
        {{ item.message }}
      </div>
    </div>
  </div>
</template>
