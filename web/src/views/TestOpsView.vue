<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { store, toast, toastError } from '../store'
import { formatCount } from '../format'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import Dialog from '../components/Dialog.vue'

const router = useRouter()

const loading = ref(false)
const error = ref('')
const dbs = ref([])
const keyword = ref('')
const selected = reactive(new Set())

const meta = ref({
  maxTables: 50,
  maxCount: 100,
  defaultCount: 5,
  columnTypes: ['STRING', 'INT32', 'INT64', 'DOUBLE', 'BOOL'],
  columnModes: ['REPLACE', 'ONCE'],
  minIntervalSeconds: 60
})

const actions = reactive({
  deleteRows: false,
  deleteCount: 5,
  addRows: false,
  addCount: 5,
  addColumn: false,
  columnType: 'STRING',
  columnMode: 'REPLACE'
})

const scheduleForm = reactive({ name: '', intervalMinutes: 5, maxRuns: '', endAt: '' })
const schedules = ref([])
const schedulesLoading = ref(false)
const busy = ref('')
const pendingRun = ref(null)
const pendingSave = ref(null)
const pendingDelete = ref(null)
const runsSchedule = ref(null)
const runs = ref([])
const runsLoading = ref(false)
let timer = null

const groups = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return (dbs.value || [])
    .map((group) => ({
      ...group,
      tables: group.tables.filter((table) => !kw || table.name.toLowerCase().includes(kw))
    }))
    .filter((group) => group.tables.length > 0)
})

const visibleNames = computed(() => groups.value.flatMap((group) => group.tables.map((table) => table.name)))
const allVisibleSelected = computed(() => visibleNames.value.length > 0
  && visibleNames.value.every((name) => selected.has(name)))
const selectedNames = computed(() => [...selected])
const enabledActions = computed(() => Number(actions.deleteRows) + Number(actions.addRows) + Number(actions.addColumn))
const canSubmit = computed(() => selected.size > 0 && enabledActions.value > 0 && !busy.value)
const clusterSchedules = computed(() => schedules.value.filter((item) => item.clusterId === store.activeId))

const actionSummary = computed(() => {
  const parts = []
  if (actions.deleteRows) parts.push(`每表随机删除 ${count(actions.deleteCount)} 行`)
  if (actions.addColumn) {
    parts.push(`每表添加 ${actions.columnType} 测试列`
      + (actions.columnMode === 'REPLACE' ? '（替换上一轮）' : '（只加一次）'))
  }
  if (actions.addRows) parts.push(`每表新增 ${count(actions.addCount)} 行`)
  return parts
})

const confirmMessage = computed(() => {
  const names = selectedNames.value
  const preview = names.slice(0, 6).join('、')
  const more = names.length > 6 ? ` 等 ${names.length} 张表` : ''
  return `将在 ${names.length} 张表（${preview}${more}）上依次执行：${actionSummary.value.join('；')}。`
    + ' 操作会真实修改表数据与结构，且不可撤销。'
})

function count(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number : 0
}

function toggleTable(name) {
  if (selected.has(name)) {
    selected.delete(name)
  } else {
    selected.add(name)
  }
}

function toggleAll() {
  if (allVisibleSelected.value) {
    visibleNames.value.forEach((name) => selected.delete(name))
  } else {
    visibleNames.value.forEach((name) => selected.add(name))
  }
}

function clearSelection() {
  selected.clear()
}

function collectActions() {
  return {
    deleteRows: !!actions.deleteRows,
    deleteCount: count(actions.deleteCount),
    addRows: !!actions.addRows,
    addCount: count(actions.addCount),
    addColumn: !!actions.addColumn,
    columnType: actions.columnType,
    columnMode: actions.columnMode
  }
}

function validate() {
  if (!selected.size) {
    toast('请至少选择一张表', 'warn')
    return false
  }
  if (selected.size > meta.value.maxTables) {
    toast(`单次最多选择 ${meta.value.maxTables} 张表`, 'warn')
    return false
  }
  if (!enabledActions.value) {
    toast('请至少勾选一个测试动作', 'warn')
    return false
  }
  if (actions.deleteRows && !validCount(actions.deleteCount)) return false
  if (actions.addRows && !validCount(actions.addCount)) return false
  return true
}

function validCount(value) {
  const number = Number(value)
  if (!Number.isInteger(number) || number < 1 || number > meta.value.maxCount) {
    toast(`条数需为 1..${meta.value.maxCount} 的整数`, 'warn')
    return false
  }
  return true
}

function askRun() {
  if (!validate()) return
  pendingRun.value = { tables: selectedNames.value, actions: collectActions() }
}

async function doRun() {
  const target = pendingRun.value
  if (!target) return
  busy.value = 'run'
  try {
    const job = await api.runTestOps(target.tables, target.actions)
    pendingRun.value = null
    toast(`已提交测试任务，共 ${job.totalEstimate ?? '—'} 个动作`)
    router.push('/jobs')
  } catch (e) {
    toastError(e)
  } finally {
    busy.value = ''
  }
}

function askSave() {
  if (!validate()) return
  const minutes = Number(scheduleForm.intervalMinutes)
  if (!Number.isFinite(minutes) || minutes * 60 < meta.value.minIntervalSeconds) {
    toast(`执行间隔至少 ${Math.ceil(meta.value.minIntervalSeconds / 60)} 分钟`, 'warn')
    return
  }
  if (scheduleForm.maxRuns) {
    const maxRuns = Number(scheduleForm.maxRuns)
    if (!Number.isInteger(maxRuns) || maxRuns < 1 || maxRuns > 10000) {
      toast('最多执行次数需为 1..10000 的整数', 'warn')
      return
    }
  }
  let endAtMs = null
  if (scheduleForm.endAt) {
    endAtMs = new Date(scheduleForm.endAt).getTime()
    if (isNaN(endAtMs) || endAtMs <= Date.now()) {
      toast('结束时间需晚于当前时间', 'warn')
      return
    }
  }
  pendingSave.value = {
    name: scheduleForm.name.trim() || null,
    enabled: true,
    intervalSeconds: Math.round(minutes * 60),
    maxRuns: scheduleForm.maxRuns ? Number(scheduleForm.maxRuns) : null,
    endAtMs,
    tables: selectedNames.value,
    actions: collectActions()
  }
}

async function doSave() {
  const payload = pendingSave.value
  if (!payload) return
  busy.value = 'save'
  try {
    await api.createTestSchedule(payload)
    pendingSave.value = null
    scheduleForm.name = ''
    toast('定时任务已保存并启用')
    await loadSchedules()
  } catch (e) {
    toastError(e)
  } finally {
    busy.value = ''
  }
}

async function toggleSchedule(schedule) {
  try {
    await api.toggleTestSchedule(schedule.id, !schedule.enabled)
    toast(schedule.enabled ? '定时任务已暂停' : '定时任务已启用')
    await loadSchedules()
  } catch (e) {
    toastError(e)
  }
}

async function runScheduleNow(schedule) {
  try {
    const job = await api.runTestSchedule(schedule.id)
    toast(`已提交任务：${schedule.name}（${job.totalEstimate ?? '—'} 个动作）`)
    await loadSchedules()
  } catch (e) {
    toastError(e)
  }
}

function askDeleteSchedule(schedule) {
  pendingDelete.value = schedule
}

async function doDeleteSchedule() {
  const target = pendingDelete.value
  if (!target) return
  busy.value = 'delete'
  try {
    await api.deleteTestSchedule(target.id)
    pendingDelete.value = null
    toast('定时任务已删除')
    await loadSchedules()
  } catch (e) {
    toastError(e)
  } finally {
    busy.value = ''
  }
}

async function openRuns(schedule) {
  runsSchedule.value = schedule
  runs.value = []
  runsLoading.value = true
  try {
    runs.value = await api.testScheduleRuns(schedule.id)
  } catch (e) {
    toastError(e)
  } finally {
    runsLoading.value = false
  }
}

function formatTime(ms) {
  if (!ms) return '—'
  const date = new Date(Number(ms))
  if (isNaN(date.getTime())) return '—'
  const pad = (value) => String(value).padStart(2, '0')
  return `${date.getMonth() + 1}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function intervalText(seconds) {
  const minutes = Math.round(Number(seconds) / 60)
  return minutes >= 60 && minutes % 60 === 0 ? `每 ${minutes / 60} 小时` : `每 ${minutes} 分钟`
}

function scheduleActionText(schedule) {
  const spec = schedule.actions || {}
  const parts = []
  if (spec.deleteRows) parts.push(`删 ${spec.deleteCount}`)
  if (spec.addColumn) parts.push('加列')
  if (spec.addRows) parts.push(`增 ${spec.addCount}`)
  return parts.join(' + ') || '—'
}

function tableText(schedule) {
  const tables = schedule.tables || []
  if (tables.length <= 2) return tables.join('、')
  return `${tables.slice(0, 2).join('、')} 等 ${tables.length} 张`
}

function stateClass(state) {
  if (state === 'SUCCEEDED') return 'ok'
  if (state === 'FAILED') return 'bad'
  if (state === 'RUNNING' || state === 'PENDING' || state === 'SKIPPED') return 'warn'
  return ''
}

async function loadTables() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.listTables()
    dbs.value = data.dbs || []
    const existing = new Set((data.dbs || []).flatMap((group) => group.tables.map((table) => table.name)))
    const selectedNow = [...selected]
    selectedNow.forEach((name) => {
      if (!existing.has(name)) selected.delete(name)
    })
  } catch (e) {
    error.value = e.message
    toastError(e)
  } finally {
    loading.value = false
  }
}

async function loadSchedules(silent = false) {
  if (!silent) schedulesLoading.value = true
  try {
    schedules.value = await api.listTestSchedules()
  } catch (e) {
    // 轮询失败不打断页面，首屏错误由 loadTables 提示
  } finally {
    if (!silent) schedulesLoading.value = false
  }
}

async function loadMeta() {
  try {
    const data = await api.testMeta()
    meta.value = { ...meta.value, ...data }
    actions.deleteCount = data.defaultCount ?? actions.deleteCount
    actions.addCount = data.defaultCount ?? actions.addCount
  } catch (e) {
    // 使用前端默认值
  }
}

onMounted(async () => {
  await Promise.all([loadMeta(), loadTables(), loadSchedules()])
  timer = setInterval(() => {
    if (!document.hidden) loadSchedules(true)
  }, 5000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div>
    <div class="ops-layout">
      <div class="card picker">
        <div class="card-head">
          <div class="card-title">选择表</div>
          <div class="card-sub">已选 {{ selected.size }} 张</div>
        </div>
        <div class="card-body" style="padding: 10px">
          <input v-model="keyword" class="input" placeholder="搜索表名" style="width: 100%" />
          <div class="row tight" style="margin: 8px 0">
            <button class="btn ghost sm" @click="toggleAll" :disabled="!visibleNames.length">
              {{ allVisibleSelected ? '取消全选' : '全选当前' }}
            </button>
            <button class="btn ghost sm" @click="clearSelection" :disabled="!selected.size">清空</button>
          </div>
          <div v-if="loading && !dbs.length" class="loading"><span class="spinner"></span>加载表清单…</div>
          <div v-else-if="!groups.length" class="empty">没有匹配的表</div>
          <div v-else class="picker-scroll">
            <div v-for="group in groups" :key="group.db">
              <div class="picker-db">
                <span>{{ group.db }}</span>
                <span class="muted">{{ group.tables.length }}</span>
              </div>
              <label v-for="table in group.tables" :key="table.name" class="picker-row">
                <input type="checkbox" :checked="selected.has(table.name)" @change="toggleTable(table.name)" />
                <span class="mono picker-name" :title="table.name">{{ table.shortName }}</span>
                <span class="muted">{{ formatCount(table.liveRowCount) }}</span>
              </label>
            </div>
          </div>
        </div>
      </div>

      <div>
        <div v-if="error" class="banner error" style="margin-bottom: 12px">{{ error }}</div>

        <div class="card">
          <div class="card-head">
            <div class="card-title">测试动作</div>
            <div class="card-sub">可多选，固定按 删除 → 改结构 → 新增 执行</div>
          </div>
          <div class="card-body">
            <div class="action-row">
              <label class="checkbox action-name">
                <input type="checkbox" v-model="actions.deleteRows" />删除数据
              </label>
              <span class="hint">扫描主键后随机删除</span>
              <span class="action-param">
                每表 <input v-model.number="actions.deleteCount" class="input" type="number" min="1" :max="meta.maxCount"
                  :disabled="!actions.deleteRows" style="width: 60px" /> 行
              </span>
            </div>
            <div class="action-row">
              <label class="checkbox action-name">
                <input type="checkbox" v-model="actions.addColumn" />修改表结构
              </label>
              <span class="hint">Kudu 只能加列，列名自动生成</span>
              <span class="action-param">
                <select v-model="actions.columnType" class="select" :disabled="!actions.addColumn">
                  <option v-for="type in meta.columnTypes" :key="type" :value="type">{{ type }}</option>
                </select>
                <select v-model="actions.columnMode" class="select" :disabled="!actions.addColumn">
                  <option value="REPLACE">替换上轮测试列</option>
                  <option value="ONCE">只加一次</option>
                </select>
              </span>
            </div>
            <div class="action-row">
              <label class="checkbox action-name">
                <input type="checkbox" v-model="actions.addRows" />添加数据
              </label>
              <span class="hint">按 schema 生成合法值，主键自动避让</span>
              <span class="action-param">
                每表 <input v-model.number="actions.addCount" class="input" type="number" min="1" :max="meta.maxCount"
                  :disabled="!actions.addRows" style="width: 60px" /> 行
              </span>
            </div>

            <div class="banner info" v-if="!enabledActions" style="margin-top: 12px">
              至少勾选一个动作；删除与新增可以同时生效。
            </div>

            <div class="schedule-form">
              <div class="field">
                <label class="label">任务名称（可选）</label>
                <input v-model="scheduleForm.name" class="input" placeholder="自动生成" maxlength="64" />
              </div>
              <div class="field" style="width: 120px">
                <label class="label">间隔（分钟）</label>
                <input v-model.number="scheduleForm.intervalMinutes" class="input" type="number" min="1" max="1440" />
              </div>
              <div class="field" style="width: 120px">
                <label class="label">最多执行次数</label>
                <input v-model="scheduleForm.maxRuns" class="input" type="number" min="1" max="10000" placeholder="不限" />
              </div>
              <div class="field" style="width: 200px">
                <label class="label">结束时间（可选）</label>
                <input v-model="scheduleForm.endAt" class="input" type="datetime-local" />
              </div>
            </div>

            <div class="row" style="margin-top: 14px; align-items: center">
              <button class="btn primary" @click="askRun" :disabled="!canSubmit">
                <span v-if="busy === 'run'" class="spinner"></span>立即执行一次
              </button>
              <button class="btn" @click="askSave" :disabled="!canSubmit || busy === 'save'">
                <span v-if="busy === 'save'" class="spinner"></span>保存为定时任务
              </button>
              <span class="hint">
                {{ selected.size }} 张表 × {{ enabledActions }} 个动作{{ actionSummary.length ? '：' + actionSummary.join('；') : '' }}
              </span>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="card-head">
            <div class="card-title">定时任务</div>
            <div class="card-sub">当前平台 · 每 5 秒检查到期任务</div>
            <div class="card-actions">
              <button class="btn sm" @click="loadSchedules" :disabled="schedulesLoading">
                <span v-if="schedulesLoading" class="spinner"></span>刷新
              </button>
            </div>
          </div>
          <div class="card-body flush">
            <div v-if="!clusterSchedules.length" class="empty">还没有定时任务</div>
            <div v-else class="grid-wrap">
              <table class="grid schedule-grid">
                <thead>
                  <tr>
                    <th>名称</th>
                    <th>表</th>
                    <th>动作</th>
                    <th>调度</th>
                    <th>上次结果</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="schedule in clusterSchedules" :key="schedule.id">
                    <td>
                      <div>{{ schedule.name }}</div>
                      <div class="hint">
                        {{ schedule.enabled ? '启用中' : '已暂停' }}
                        <span v-if="schedule.maxRuns">· 最多 {{ schedule.maxRuns }} 次</span>
                      </div>
                    </td>
                    <td class="mono" :title="schedule.tables.join('\n')">{{ tableText(schedule) }}</td>
                    <td>{{ scheduleActionText(schedule) }}</td>
                    <td>
                      <div>{{ intervalText(schedule.intervalSeconds) }}</div>
                      <div class="hint">
                        已执行 {{ schedule.runCount }} 次<template v-if="schedule.enabled"> · 下次 {{ formatTime(schedule.nextRunAtMs) }}</template>
                      </div>
                    </td>
                    <td class="wrap">
                      <div class="status">
                        <span class="status-dot" :class="stateClass(schedule.lastState)"></span>
                        {{ schedule.lastState || '—' }}
                      </div>
                      <div class="hint" :title="schedule.lastMessage">{{ schedule.lastMessage || '' }}</div>
                    </td>
                    <td class="nowrap">
                      <button class="btn ghost sm" @click="runScheduleNow(schedule)">执行</button>
                      <button class="btn ghost sm" @click="openRuns(schedule)">记录</button>
                      <button class="btn ghost sm" @click="toggleSchedule(schedule)">
                        {{ schedule.enabled ? '暂停' : '启用' }}
                      </button>
                      <button class="btn ghost sm" style="color: var(--danger)" @click="askDeleteSchedule(schedule)">
                        删除
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <div class="hint" style="margin-top: 8px">
          修改表结构只支持加可空列；定时任务每次执行会真实删改数据，适合验证 Kudu 全量 / 增量备份是否捕获变化。
          与 truncate、删表、删库任务冲突时会跳过本轮并记录。
        </div>
      </div>
    </div>

    <ConfirmDialog
      v-if="pendingRun"
      title="立即执行测试动作"
      :message="confirmMessage"
      confirm-text="开始执行"
      :busy="busy === 'run'"
      @confirm="doRun"
      @close="pendingRun = null"
    />

    <ConfirmDialog
      v-if="pendingSave"
      title="保存并启用定时任务"
      :message="confirmMessage + ` 之后${pendingSave.maxRuns ? '最多执行 ' + pendingSave.maxRuns + ' 次' : '持续'}按间隔自动重复。`"
      confirm-text="保存并启用"
      :busy="busy === 'save'"
      @confirm="doSave"
      @close="pendingSave = null"
    />

    <ConfirmDialog
      v-if="pendingDelete"
      title="删除定时任务"
      :message="`将删除定时任务「${pendingDelete.name}」，执行记录也会一并清除。`"
      confirm-text="删除任务"
      :busy="busy === 'delete'"
      @confirm="doDeleteSchedule"
      @close="pendingDelete = null"
    />

    <Dialog v-if="runsSchedule" :title="`执行记录 · ${runsSchedule.name}`" wide @close="runsSchedule = null">
      <div v-if="runsLoading" class="loading"><span class="spinner"></span>加载记录…</div>
      <div v-else-if="!runs.length" class="empty">还没有执行记录</div>
      <div v-else class="run-list">
        <div v-for="run in runs" :key="run.id" class="run-item">
          <div class="row" style="align-items: center">
            <span class="status">
              <span class="status-dot" :class="stateClass(run.state)"></span>{{ run.state }}
            </span>
            <span class="muted">{{ formatTime(run.startedAtMs) }}</span>
            <span class="tag plain">{{ run.trigger === 'MANUAL' ? '手动' : '定时' }}</span>
          </div>
          <div class="hint" style="margin-top: 4px">{{ run.message }}</div>
          <div v-if="run.tables && run.tables.length" class="hint mono" style="margin-top: 4px">
            <span v-for="item in run.tables" :key="item.table" style="margin-right: 10px">
              {{ item.table }}（删 {{ item.deleted }} / 增 {{ item.added }}<template v-if="item.column"> / 加列 {{ item.column }}</template>）
            </span>
          </div>
        </div>
      </div>
    </Dialog>
  </div>
</template>

<style scoped>
.ops-layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}
.picker-scroll {
  max-height: calc(100vh - 300px);
  overflow: auto;
  margin: 0 -4px;
}
.picker-db {
  display: flex;
  justify-content: space-between;
  padding: 8px 4px 4px;
  font-size: 11px;
  color: var(--text-2);
  border-bottom: 1px solid var(--border);
}
.picker-row {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 4px;
  font-size: 12px;
  cursor: pointer;
  border-radius: var(--radius);
}
.picker-row:hover { background: var(--surface-2); }
.picker-row input { accent-color: var(--accent); }
.picker-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}
.picker-row .muted { font-size: 11px; margin-left: auto; }
.action-row {
  display: grid;
  grid-template-columns: 130px minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--border);
}
.action-name { color: var(--text); }
.action-param { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-2); }
.schedule-form {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--border-strong);
}
.run-list { display: flex; flex-direction: column; gap: 10px; }
.schedule-grid { min-width: 820px; }
.run-item { border: 1px solid var(--border); border-radius: var(--radius); padding: 10px 12px; background: var(--surface-2); }
@media (max-width: 980px) {
  .ops-layout { grid-template-columns: minmax(0, 1fr); }
  .picker-scroll { max-height: 300px; }
  .action-row { grid-template-columns: 120px minmax(0, 1fr); }
  .action-param { grid-column: 2; justify-content: flex-start; }
}
</style>
