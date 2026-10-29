<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { api } from '../api'
import { store, loadClusters, toast, toastError } from '../store'
import { formatCount } from '../format'

const clusters = computed(() => store.clusters)
const leftCluster = ref('')
const rightCluster = ref('')
const leftTable = ref('')
const rightTable = ref('')
const leftDbs = ref([])
const rightDbs = ref([])
const leftLoading = ref(false)
const rightLoading = ref(false)
const loadSeq = { left: 0, right: 0 }
const options = reactive({ compareData: true, maxRows: 200000, sampleLimit: 10 })
const task = ref(null)
const starting = ref(false)
const error = ref('')
let timer = null

const running = computed(() => !!task.value && ['PENDING', 'RUNNING'].includes(task.value.state))
const result = computed(() => task.value?.result || null)
const canStart = computed(() => !!leftCluster.value && !!rightCluster.value
  && !!leftTable.value && !!rightTable.value && !running.value && !starting.value)
const leftTableCount = computed(() => leftDbs.value.reduce((sum, group) => sum + group.tables.length, 0))
const rightTableCount = computed(() => rightDbs.value.reduce((sum, group) => sum + group.tables.length, 0))

const sampleGroups = computed(() => {
  const data = result.value?.data
  if (!data) return []
  return [
    { kind: 'missing', title: '目标缺失（源有目标无）', count: data.missing, items: data.missingSamples || [] },
    { kind: 'extra', title: '目标多出（源无目标有）', count: data.extra, items: data.extraSamples || [] },
    { kind: 'mismatch', title: '内容不一致', count: data.mismatch, items: data.mismatchSamples || [] }
  ]
})

function sideState(side) {
  return side === 'left'
    ? { cluster: leftCluster, table: leftTable, dbs: leftDbs, loading: leftLoading }
    : { cluster: rightCluster, table: rightTable, dbs: rightDbs, loading: rightLoading }
}

async function loadSide(side) {
  const state = sideState(side)
  const seq = ++loadSeq[side]
  if (!state.cluster.value) {
    state.dbs.value = []
    state.loading.value = false
    return
  }
  state.loading.value = true
  try {
    const data = await api.compareTables(state.cluster.value)
    if (seq !== loadSeq[side]) return
    state.dbs.value = data.dbs || []
    const names = new Set(state.dbs.value.flatMap((group) => group.tables.map((table) => table.name)))
    if (state.table.value && !names.has(state.table.value)) {
      state.table.value = ''
    }
  } catch (e) {
    if (seq !== loadSeq[side]) return
    state.dbs.value = []
    toastError(e)
  } finally {
    if (seq === loadSeq[side]) state.loading.value = false
  }
}

function onClusterChange(side) {
  const state = sideState(side)
  state.table.value = ''
  loadSide(side)
}

function stateText(state) {
  return { PENDING: '等待中', RUNNING: '比对中', SUCCEEDED: '已完成', FAILED: '失败', CANCELED: '已取消' }[state] || state
}

function stateClass(state) {
  if (state === 'SUCCEEDED') return 'ok'
  if (state === 'FAILED') return 'bad'
  if (state === 'RUNNING' || state === 'PENDING' || state === 'CANCELED') return 'warn'
  return ''
}

function kindText(kind) {
  return { MISSING_IN_RIGHT: '目标缺失', EXTRA_IN_RIGHT: '目标多出', CHANGED: '定义不同' }[kind] || kind
}

function rowsText(side) {
  if (!side) return '—'
  if (!side.exists) return '不存在'
  return side.rows == null ? '未扫描' : formatCount(side.rows) + ' 行'
}

async function startCompare() {
  if (!canStart.value) return
  const maxRows = Number(options.maxRows)
  const sampleLimit = Number(options.sampleLimit)
  if (!Number.isInteger(maxRows) || maxRows < 1) {
    toast('最大扫描行数需为正整数', 'warn')
    return
  }
  if (!Number.isInteger(sampleLimit) || sampleLimit < 0 || sampleLimit > 50) {
    toast('差异样本数需在 0..50', 'warn')
    return
  }
  starting.value = true
  error.value = ''
  stopPolling()
  task.value = null
  try {
    task.value = await api.startCompare({
      leftCluster: leftCluster.value,
      leftTable: leftTable.value,
      rightCluster: rightCluster.value,
      rightTable: rightTable.value,
      compareData: options.compareData,
      maxRows,
      sampleLimit
    })
    timer = setInterval(poll, 700)
  } catch (e) {
    error.value = e.message
    toastError(e)
  } finally {
    starting.value = false
  }
}

async function poll() {
  if (!task.value) {
    stopPolling()
    return
  }
  try {
    task.value = await api.compareTask(task.value.id)
    if (!['PENDING', 'RUNNING'].includes(task.value.state)) {
      stopPolling()
    }
  } catch (e) {
    stopPolling()
    error.value = e.message
  }
}

async function cancelCompare() {
  if (!task.value) return
  try {
    task.value = await api.cancelCompare(task.value.id)
  } catch (e) {
    toastError(e)
  }
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

onMounted(async () => {
  if (!store.clusters.length) {
    try {
      await loadClusters()
    } catch (e) {
      // 平台清单加载失败时页面仍可显示提示
    }
  }
  const active = store.activeId || store.clusters[0]?.id || ''
  leftCluster.value = active
  rightCluster.value = active
  await Promise.all([loadSide('left'), loadSide('right')])
})

onUnmounted(stopPolling)
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        <div class="card-title">表比对</div>
        <div class="card-sub">比对恢复前后的表结构与全量数据，支持跨平台</div>
      </div>
      <div class="card-body">
        <div class="compare-grid">
          <div class="compare-side">
            <div class="side-head">
              <span class="tag plain">源表</span>
              <span class="hint">{{ leftLoading ? '加载中…' : leftTableCount + ' 张可选' }}</span>
            </div>
            <div class="field">
              <label class="label">平台</label>
              <select v-model="leftCluster" class="select" :disabled="running" @change="onClusterChange('left')">
                <option v-if="!clusters.length" value="">未配置平台</option>
                <option v-for="cluster in clusters" :key="cluster.id" :value="cluster.id">{{ cluster.name }}</option>
              </select>
            </div>
            <div class="field">
              <label class="label">表</label>
              <select v-model="leftTable" class="select mono" :disabled="running || leftLoading">
                <option value="">选择表…</option>
                <optgroup v-for="group in leftDbs" :key="group.db" :label="group.db">
                  <option v-for="table in group.tables" :key="table.name" :value="table.name">
                    {{ table.shortName }} · {{ formatCount(table.liveRowCount) }} 行
                  </option>
                </optgroup>
              </select>
            </div>
          </div>

          <div class="compare-side">
            <div class="side-head">
              <span class="tag plain">目标表</span>
              <span class="hint">{{ rightLoading ? '加载中…' : rightTableCount + ' 张可选' }}</span>
            </div>
            <div class="field">
              <label class="label">平台</label>
              <select v-model="rightCluster" class="select" :disabled="running" @change="onClusterChange('right')">
                <option v-if="!clusters.length" value="">未配置平台</option>
                <option v-for="cluster in clusters" :key="cluster.id" :value="cluster.id">{{ cluster.name }}</option>
              </select>
            </div>
            <div class="field">
              <label class="label">表</label>
              <select v-model="rightTable" class="select mono" :disabled="running || rightLoading">
                <option value="">选择表…</option>
                <optgroup v-for="group in rightDbs" :key="group.db" :label="group.db">
                  <option v-for="table in group.tables" :key="table.name" :value="table.name">
                    {{ table.shortName }} · {{ formatCount(table.liveRowCount) }} 行
                  </option>
                </optgroup>
              </select>
            </div>
          </div>
        </div>

        <div class="compare-options">
          <label class="checkbox" style="height: 28px">
            <input type="checkbox" v-model="options.compareData" :disabled="running" />比对数据
          </label>
          <div class="field" style="width: 140px">
            <label class="label">最大扫描行数</label>
            <input v-model.number="options.maxRows" class="input" type="number" min="1" max="1000000" :disabled="running" />
          </div>
          <div class="field" style="width: 110px">
            <label class="label">差异样本数</label>
            <input v-model.number="options.sampleLimit" class="input" type="number" min="0" max="50" :disabled="running" />
          </div>
          <button class="btn primary" @click="startCompare" :disabled="!canStart">
            <span v-if="starting" class="spinner"></span>开始比对
          </button>
          <button v-if="running" class="btn" @click="cancelCompare">取消比对</button>
        </div>

        <div class="hint" style="margin-top: 8px">
          整表内容载入内存对账，建议单表 ≤ 20 万行；列顺序、encoding、compression、副本数与分区不参与比对。
          比对期间请停止写入，否则结果可能自相矛盾。
        </div>
        <div v-if="error" class="banner error" style="margin-top: 10px">{{ error }}</div>
      </div>
    </div>

    <div v-if="task" class="card">
      <div class="card-head">
        <div class="card-title">比对结果</div>
        <div class="card-sub">
          源已扫描 {{ formatCount(task.leftScanned) }} 行 · 目标已扫描 {{ formatCount(task.rightScanned) }} 行
          <template v-if="result"> · 耗时 {{ result.elapsedMs }} ms</template>
        </div>
        <div class="card-actions">
          <span class="status">
            <span class="status-dot" :class="stateClass(task.state)"></span>{{ stateText(task.state) }}
          </span>
        </div>
      </div>
      <div class="card-body">
        <div v-if="running" class="loading"><span class="spinner"></span>正在扫描并比对两张表…</div>
        <div v-else-if="!result" class="empty">{{ task.message || '没有比对结果' }}</div>

        <template v-else>
          <div class="result-banner" :class="result.consistent ? 'ok' : 'bad'">
            {{ result.message }}
          </div>

          <div class="compare-summary">
            <div class="summary-card">
              <div class="summary-title">源表</div>
              <div class="mono">{{ result.left.table }}</div>
              <div class="hint">
                {{ result.left.clusterName }} · {{ result.left.columns }} 列 · {{ rowsText(result.left) }}
              </div>
            </div>
            <div class="summary-card">
              <div class="summary-title">目标表</div>
              <div class="mono">{{ result.right.table }}</div>
              <div class="hint">
                {{ result.right.clusterName }} · {{ result.right.columns }} 列 · {{ rowsText(result.right) }}
              </div>
            </div>
          </div>

          <template v-if="!result.left.exists || !result.right.exists">
            <div class="banner warn" style="margin-top: 12px">
              有一侧表不存在，未进行结构与数据比对。
            </div>
          </template>

          <template v-else>
            <div class="section-title">表结构</div>
            <div v-if="result.schemaEqual && result.primaryKeyEqual" class="hint" style="color: var(--ok)">
              一致（{{ result.left.columns }} 列；列顺序、encoding、compression 不参与比对）
            </div>
            <template v-else>
              <div v-if="!result.schemaEqual" class="grid-wrap">
                <table class="grid">
                  <thead>
                    <tr>
                      <th>差异</th>
                      <th>列</th>
                      <th>源</th>
                      <th>目标</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="diff in result.columnDiffs" :key="diff.kind + diff.name">
                      <td>{{ kindText(diff.kind) }}</td>
                      <td class="mono">{{ diff.name }}</td>
                      <td class="mono wrap">{{ diff.left || '—' }}</td>
                      <td class="mono wrap">{{ diff.right || '—' }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-if="!result.primaryKeyEqual" class="banner warn" style="margin-top: 8px">
                主键顺序不一致：源 {{ result.leftPrimaryKey.join('、') || '—' }}
                / 目标 {{ result.rightPrimaryKey.join('、') || '—' }}
              </div>
            </template>

            <div class="section-title">数据</div>
            <template v-if="result.data">
              <div class="metric-grid">
                <div class="metric">
                  <div class="metric-label">一致行</div>
                  <div class="metric-value">{{ formatCount(result.data.matched) }}</div>
                </div>
                <div class="metric">
                  <div class="metric-label">目标缺失</div>
                  <div class="metric-value" :class="{ bad: result.data.missing > 0 }">
                    {{ formatCount(result.data.missing) }}
                  </div>
                </div>
                <div class="metric">
                  <div class="metric-label">目标多出</div>
                  <div class="metric-value" :class="{ bad: result.data.extra > 0 }">
                    {{ formatCount(result.data.extra) }}
                  </div>
                </div>
                <div class="metric">
                  <div class="metric-label">内容不一致</div>
                  <div class="metric-value" :class="{ bad: result.data.mismatch > 0 }">
                    {{ formatCount(result.data.mismatch) }}
                  </div>
                </div>
              </div>

              <div
                v-if="!result.data.missing && !result.data.extra && !result.data.mismatch"
                class="hint"
                style="margin-top: 8px; color: var(--ok)"
              >
                全部行内容一致（{{ formatCount(result.data.matched) }} 行）
              </div>

              <template v-for="group in sampleGroups" :key="group.kind">
                <div v-if="group.items.length" class="sample-group">
                  <div class="sample-title">
                    {{ group.title }} · 共 {{ formatCount(group.count) }} 行，展示前 {{ group.items.length }} 条
                  </div>
                  <pre
                    v-for="(sample, index) in group.items"
                    :key="group.kind + index"
                    class="sample-pre"
                  >{{ sample.key }}<template v-if="sample.leftRow">
源  : {{ sample.leftRow }}</template><template v-if="sample.rightRow">
目标: {{ sample.rightRow }}</template></pre>
                </div>
              </template>
            </template>
            <div v-else-if="!result.schemaEqual" class="hint">
              表结构不一致，已跳过数据比对。
            </div>
            <div v-else class="hint">未比对数据（选项已关闭）。</div>
          </template>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.compare-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 14px;
}
.compare-side {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface-2);
}
.side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.compare-options {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: flex-end;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--border-strong);
}
.compare-summary {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  margin-top: 12px;
}
.summary-card {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 10px 12px;
}
.summary-title {
  font-size: 11px;
  color: var(--text-3);
  margin-bottom: 4px;
}
.section-title {
  font-size: 13px;
  font-weight: 600;
  margin: 16px 0 8px;
  padding-top: 10px;
  border-top: 1px solid var(--border);
}
.result-banner {
  padding: 8px 12px;
  border-left: 3px solid;
  border-radius: var(--radius);
  font-size: 12px;
}
.result-banner.ok {
  border-color: var(--ok);
  background: var(--ok-weak);
  color: #245a44;
}
.result-banner.bad {
  border-color: var(--danger);
  background: var(--danger-weak);
  color: #6d2a20;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}
.metric {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 10px;
}
.metric-label {
  font-size: 11px;
  color: var(--text-3);
}
.metric-value {
  font-size: 20px;
  font-weight: 500;
  margin-top: 2px;
}
.metric-value.bad {
  color: var(--danger);
}
.sample-group {
  margin-top: 10px;
}
.sample-title {
  font-size: 12px;
  color: var(--text-2);
  margin-bottom: 4px;
}
.sample-pre {
  margin: 0 0 6px;
  padding: 8px 10px;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  font-family: var(--mono);
  font-size: 11px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 140px;
  overflow: auto;
}
@media (max-width: 900px) {
  .compare-grid,
  .compare-summary {
    grid-template-columns: minmax(0, 1fr);
  }
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
