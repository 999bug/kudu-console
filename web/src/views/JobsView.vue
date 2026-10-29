<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api'
import { store, toast, toastError } from '../store'
import { formatCount } from '../format'

const jobs = ref([])
const loading = ref(false)
const error = ref('')
let timer = null

function clusterName(id) {
  return store.clusters.find((c) => c.id === id)?.name || id || '—'
}

async function load() {
  loading.value = true
  try {
    jobs.value = await api.jobs()
    error.value = ''
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function cancel(job) {
  try {
    await api.cancelJob(job.id)
    toast('已请求取消任务 ' + job.id, 'warn')
    load()
  } catch (e) {
    toastError(e)
  }
}

function stateClass(state) {
  if (state === 'SUCCEEDED') return 'ok'
  if (state === 'FAILED') return 'bad'
  if (state === 'RUNNING' || state === 'PENDING') return 'warn'
  return ''
}

function progress(job) {
  if (!job.totalEstimate) return null
  return Math.min(100, Math.round((job.processed / job.totalEstimate) * 100))
}

onMounted(() => {
  load()
  timer = setInterval(() => {
    if (!document.hidden) load()
  }, 3000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="card">
    <div class="card-head">
      <div class="card-title">任务中心</div>
      <div class="card-sub">清空表按行计数，删库 / 按前缀删表按表计数；最多保留 50 条</div>
      <div class="card-actions">
        <button class="btn sm" @click="load" :disabled="loading">
          <span v-if="loading" class="spinner"></span>刷新
        </button>
      </div>
    </div>

    <div v-if="error" class="banner error" style="margin: 12px">{{ error }}</div>

    <div class="card-body flush">
      <div v-if="!jobs.length" class="empty">还没有任务</div>
      <div v-else class="grid-wrap">
        <table class="grid">
          <thead>
            <tr>
              <th>状态</th>
              <th>类型</th>
              <th>平台</th>
              <th>对象</th>
              <th class="num">已处理</th>
              <th class="num">已删除</th>
              <th class="num">失败</th>
              <th class="num">速度</th>
              <th>开始时间</th>
              <th>结束时间</th>
              <th>说明</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="job in jobs" :key="job.id">
              <td>
                <span class="status">
                  <span class="status-dot" :class="stateClass(job.state)"></span>{{ job.state }}
                </span>
              </td>
              <td class="mono">{{ job.type }}</td>
              <td>{{ clusterName(job.cluster) }}</td>
              <td class="mono">{{ job.table }}</td>
              <td class="num">{{ formatCount(job.processed) }}</td>
              <td class="num">{{ formatCount(job.deleted) }}</td>
              <td class="num" :style="job.failed ? 'color: var(--danger)' : null">{{ job.failed }}</td>
              <td class="num">{{ job.ratePerSec }}</td>
              <td class="nowrap muted">{{ job.startedAt || '—' }}</td>
              <td class="nowrap muted">{{ job.finishedAt || '—' }}</td>
              <td class="wrap muted">{{ job.message || '' }}</td>
              <td>
                <button v-if="job.cancelable" class="btn ghost sm" @click="cancel(job)">取消</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
