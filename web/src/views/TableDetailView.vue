<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api'
import { toastError } from '../store'
import { formatBytes, formatCount } from '../format'
import DataTab from '../components/DataTab.vue'
import StructureTab from '../components/StructureTab.vue'
import PartitionTab from '../components/PartitionTab.vue'
import AdvancedTab from '../components/AdvancedTab.vue'

const route = useRoute()
const router = useRouter()

const name = computed(() => (route.query.name ? String(route.query.name) : ''))
const detail = ref(null)
const loading = ref(false)
const error = ref('')
const tab = computed({
  get: () => {
    const value = String(route.query.tab || 'data')
    return ['data', 'structure', 'partition', 'advanced'].includes(value) ? value : 'data'
  },
  set: (value) => {
    router.replace({ path: '/table', query: { ...route.query, tab: value } })
  }
})

const tabs = [
  { key: 'data', label: '数据' },
  { key: 'structure', label: '结构' },
  { key: 'partition', label: '分区' },
  { key: 'advanced', label: '高级' }
]

async function load() {
  if (!name.value) {
    detail.value = null
    return
  }
  loading.value = true
  error.value = ''
  try {
    detail.value = await api.tableDetail(name.value)
  } catch (e) {
    error.value = e.message
    detail.value = null
    toastError(e)
  } finally {
    loading.value = false
  }
}

watch(name, load)
onMounted(load)

function onRenamed(newName) {
  if (!newName) {
    router.push('/tables')
  } else {
    router.push({ path: '/table', query: { name: newName } })
  }
}
</script>

<template>
  <div v-if="!name" class="card">
    <div class="empty">未指定表。请从「表管理」中选择一张表。</div>
  </div>

  <div v-else-if="loading && !detail" class="loading"><span class="spinner"></span>读取表结构…</div>

  <div v-else-if="error" class="card">
    <div class="card-head">
      <div class="card-title">{{ name }}</div>
      <div class="card-actions">
        <button class="btn sm" @click="load">重试</button>
        <router-link class="btn sm" to="/tables">返回表列表</router-link>
      </div>
    </div>
    <div class="card-body"><div class="banner error" style="margin: 0">{{ error }}</div></div>
  </div>

  <div v-else-if="detail">
    <div class="card">
      <div class="card-head">
        <div class="card-title mono">{{ detail.name }}</div>
        <span v-if="detail.impalaManaged" class="tag impala">impala 托管</span>
        <span v-if="detail.systemTable" class="tag bad">系统表</span>
        <div class="card-actions">
          <router-link class="btn sm" to="/tables">返回列表</router-link>
          <button class="btn sm" @click="load" :disabled="loading">
            <span v-if="loading" class="spinner"></span>刷新
          </button>
        </div>
      </div>
      <div class="card-body">
        <div class="stats">
          <div class="stat">
            <div class="stat-label">行数（估算）</div>
            <div class="stat-value">{{ formatCount(detail.stats?.liveRowCount) }}</div>
            <div class="stat-note">来自 master 统计，非精确值</div>
          </div>
          <div class="stat">
            <div class="stat-label">磁盘占用</div>
            <div class="stat-value">{{ formatBytes(detail.stats?.onDiskSize) }}</div>
            <div class="stat-note">仅 leader 副本</div>
          </div>
          <div class="stat">
            <div class="stat-label">副本数</div>
            <div class="stat-value">{{ detail.numReplicas }}</div>
          </div>
          <div class="stat">
            <div class="stat-label">列 / 主键</div>
            <div class="stat-value">{{ detail.columns.length }} / {{ detail.primaryKey.length }}</div>
            <div class="stat-note mono">{{ detail.primaryKey.join(', ') }}</div>
          </div>
          <div class="stat">
            <div class="stat-label">Tablet（推算）</div>
            <div class="stat-value">{{ detail.partition?.estimatedTablets ?? '—' }}</div>
          </div>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="tabs">
        <div
          v-for="item in tabs"
          :key="item.key"
          class="tab"
          :class="{ active: tab === item.key }"
          @click="tab = item.key"
        >{{ item.label }}</div>
      </div>
      <div class="card-body">
        <DataTab v-if="tab === 'data'" :detail="detail" @refresh="load" />
        <StructureTab v-else-if="tab === 'structure'" :detail="detail" @refresh="load" />
        <PartitionTab v-else-if="tab === 'partition'" :detail="detail" />
        <AdvancedTab v-else :detail="detail" @refresh="load" @renamed="onRenamed" />
      </div>
    </div>
  </div>
</template>
