<script setup>
import { computed, onUnmounted, ref } from 'vue'
import { api } from '../api'
import { toast, toastError } from '../store'
import ConfirmDialog from './ConfirmDialog.vue'
import { formatCount } from '../format'

const props = defineProps({ detail: { type: Object, required: true } })
const emit = defineEmits(['refresh', 'renamed'])

const busy = ref('')
const message = ref('')
const error = ref('')

const renameTo = ref('')
const replicas = ref(props.detail.numReplicas || 1)
const replicaHint = ref('')

const showTruncate = ref(false)
const showDrop = ref(false)
const job = ref(null)
let timer = null

const systemTable = computed(() => props.detail.systemTable === true)
const impalaManaged = computed(() => props.detail.impalaManaged === true)

async function doRename() {
  const target = renameTo.value.trim()
  if (!target || target === props.detail.name) {
    error.value = '请输入不同的新表名（含 db 前缀）'
    return
  }
  busy.value = 'rename'
  error.value = ''
  try {
    await api.renameTable(props.detail.name, target)
    toast('表已重命名：' + target)
    emit('renamed', target)
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = ''
  }
}

async function doReplicas() {
  busy.value = 'replicas'
  error.value = ''
  replicaHint.value = ''
  try {
    const res = await api.setReplicas(props.detail.name, Number(replicas.value))
    toast('副本数已改为 ' + replicas.value + '（' + res.method + '）')
    emit('refresh')
  } catch (e) {
    if (e.code === 'UNSUPPORTED') {
      replicaHint.value = e.message
    } else {
      error.value = e.message
    }
  } finally {
    busy.value = ''
  }
}

async function startTruncate() {
  error.value = ''
  try {
    job.value = await api.truncate(props.detail.name, props.detail.name)
    showTruncate.value = false
    toast('清空任务已提交')
    pollJob()
  } catch (e) {
    error.value = e.message
    showTruncate.value = false
  }
}

function pollJob() {
  if (timer) clearInterval(timer)
  timer = setInterval(async () => {
    if (!job.value) return
    try {
      job.value = await api.job(job.value.id)
      if (['SUCCEEDED', 'FAILED', 'CANCELED'].includes(job.value.state)) {
        clearInterval(timer)
        timer = null
        emit('refresh')
      }
    } catch (e) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

async function cancelJob() {
  if (!job.value) return
  try {
    await api.cancelJob(job.value.id)
    toast('已请求取消', 'warn')
  } catch (e) {
    toastError(e)
  }
}

async function doDrop() {
  busy.value = 'drop'
  try {
    await api.dropTable(props.detail.name, props.detail.name)
    toast('表已删除：' + props.detail.name)
    showDrop.value = false
    emit('renamed', '')
  } catch (e) {
    error.value = e.message
    showDrop.value = false
  } finally {
    busy.value = ''
  }
}

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div>
    <div v-if="systemTable" class="banner warn">这是一张系统表，写入类操作应避免在此执行。</div>
    <div v-if="impalaManaged" class="banner info">
      该表由 Impala 创建管理（impala:: 前缀）。修改结构后需在 Impala 执行 INVALIDATE METADATA 才能同步元数据。
    </div>

    <div v-if="error" class="banner error">{{ error }}</div>

    <div class="card">
      <div class="card-head"><div class="card-title">重命名</div></div>
      <div class="card-body">
        <div class="row">
          <div class="field grow">
            <label class="label">新表名（含 db 前缀）</label>
            <input v-model="renameTo" class="input mono" :placeholder="detail.name" />
          </div>
          <div class="field" style="justify-content: flex-end">
            <button class="btn" @click="doRename" :disabled="busy === 'rename' || systemTable">
              <span v-if="busy === 'rename'" class="spinner"></span>重命名
            </button>
          </div>
        </div>
        <div class="hint" style="margin-top: 6px">重命名不会移动数据，但会影响依赖旧表名的任务与 Impala 元数据。</div>
      </div>
    </div>

    <div class="card">
      <div class="card-head"><div class="card-title">副本数</div></div>
      <div class="card-body">
        <div class="row">
          <div class="field" style="width: 140px">
            <label class="label">目标副本数</label>
            <select v-model.number="replicas" class="select">
              <option :value="1">1</option>
              <option :value="3">3</option>
              <option :value="5">5</option>
            </select>
          </div>
          <div class="field" style="justify-content: flex-end">
            <button class="btn" @click="doReplicas" :disabled="busy === 'replicas' || systemTable">
              <span v-if="busy === 'replicas'" class="spinner"></span>应用
            </button>
          </div>
        </div>
        <div v-if="replicaHint" class="banner warn" style="margin: 10px 0 0">
          {{ replicaHint }}
        </div>
        <div class="hint" style="margin-top: 6px">
          当前为 {{ detail.numReplicas }} 副本。Kudu 1.17 的 Java 客户端没有改副本数的 API，服务端不支持时会给出 CLI 命令。
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-head">
        <div class="card-title">清空数据</div>
        <div class="card-sub">扫描主键并分批删除，可取消；不会回收磁盘空间</div>
      </div>
      <div class="card-body">
        <div v-if="job" style="margin-bottom: 12px">
          <div class="row" style="align-items: center">
            <span class="status">
              <span class="status-dot" :class="job.state === 'SUCCEEDED' ? 'ok' : job.state === 'FAILED' ? 'bad' : 'warn'"></span>
              {{ job.state }}
            </span>
            <span class="muted">已处理 {{ formatCount(job.processed) }} 行 · 已删除 {{ formatCount(job.deleted) }} 行 · {{ job.ratePerSec }} 行/秒</span>
            <button v-if="job.cancelable" class="btn sm" @click="cancelJob" style="margin-left: auto">取消任务</button>
          </div>
          <div class="hint" v-if="job.message">{{ job.message }}</div>
        </div>
        <button class="btn danger" @click="showTruncate = true" :disabled="systemTable">清空表数据…</button>
      </div>
    </div>

    <div class="card">
      <div class="card-head"><div class="card-title">删除表</div></div>
      <div class="card-body">
        <p style="margin: 0 0 10px; color: var(--text-2)">
          删除后表与数据不可恢复。需要手动输入完整表名确认。
        </p>
        <button class="btn danger-solid" @click="showDrop = true" :disabled="systemTable">删除表…</button>
      </div>
    </div>

    <ConfirmDialog
      v-if="showTruncate"
      title="清空表数据"
      :message="`将删除 ${detail.name} 的全部行（扫描 + 批量 Delete）。此操作不可恢复。`"
      :expect="detail.name"
      confirm-text="开始清空"
      @confirm="startTruncate"
      @close="showTruncate = false"
    />

    <ConfirmDialog
      v-if="showDrop"
      title="删除表"
      :message="`将永久删除表 ${detail.name} 及其全部数据。`"
      :expect="detail.name"
      confirm-text="删除表"
      :busy="busy === 'drop'"
      @confirm="doDrop"
      @close="showDrop = false"
    />
  </div>
</template>
