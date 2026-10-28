<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { toast, toastError } from '../store'
import { formatBytes, formatCount } from '../format'
import CreateTableDialog from '../components/CreateTableDialog.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'

const router = useRouter()
const loading = ref(false)
const error = ref('')
const data = ref(null)
const keyword = ref('')
const activeDb = ref('')
const showCreate = ref(false)

const deleteTable = ref(null)
const deleteDb = ref(null)
const droppingTable = ref(false)
const droppingDb = ref(false)

const groups = computed(() => {
  const list = data.value?.dbs || []
  const kw = keyword.value.trim().toLowerCase()
  return list
    .filter((group) => !activeDb.value || group.db === activeDb.value)
    .map((group) => ({
      ...group,
      tables: group.tables.filter((table) => !kw || table.name.toLowerCase().includes(kw))
    }))
    .filter((group) => group.tables.length > 0)
})

const visibleCount = computed(() => groups.value.reduce((sum, group) => sum + group.tables.length, 0))

const dbDropMessage = computed(() => {
  if (!deleteDb.value) return ''
  const group = deleteDb.value
  const names = group.tables.map((t) => t.name)
  const preview = names.slice(0, 6).join('、')
  const more = names.length > 6 ? ` 等 ${names.length} 张表` : ''
  const impala = group.tables.some((t) => t.impalaManaged)
  return `将永久删除库「${group.db}」下的 ${names.length} 张表：${preview}${more}。`
    + (impala ? ' 其中包含 Impala 托管的表，删除后 Impala 元数据会失效，需要执行 INVALIDATE METADATA。' : '')
    + ' 该操作不可恢复。'
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await api.listTables()
  } catch (e) {
    error.value = e.message
    toastError(e)
  } finally {
    loading.value = false
  }
}

function openTable(name) {
  router.push({ path: '/table', query: { name } })
}

function onCreated(name) {
  showCreate.value = false
  load()
  if (name) openTable(name)
}

async function confirmDropTable() {
  const target = deleteTable.value
  if (!target) return
  droppingTable.value = true
  try {
    await api.dropTable(target.name, target.name)
    toast('已删除表：' + target.name)
    deleteTable.value = null
    await load()
  } catch (e) {
    toastError(e)
  } finally {
    droppingTable.value = false
  }
}

async function confirmDropDb() {
  const target = deleteDb.value
  if (!target) return
  droppingDb.value = true
  try {
    const job = await api.dropDb(target.db, target.db)
    toast(`已提交删除任务：${target.db}（${job.totalEstimate ?? target.tables.length} 张表）`)
    deleteDb.value = null
    router.push('/jobs')
  } catch (e) {
    toastError(e)
  } finally {
    droppingDb.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="split">
    <div class="card db-nav">
      <div class="card-head" style="padding: 8px 10px">
        <div class="card-title" style="font-size: 12px">库 / 分组</div>
      </div>
      <div style="padding: 6px">
        <div class="db-nav-item" :class="{ active: activeDb === '' }" @click="activeDb = ''">
          <span>全部</span><span class="muted">{{ data?.totalTables ?? '—' }}</span>
        </div>
        <div
          v-for="group in data?.dbs || []"
          :key="group.db"
          class="db-nav-item"
          :class="{ active: activeDb === group.db }"
          @click="activeDb = group.db"
        >
          <span style="overflow: hidden; text-overflow: ellipsis">{{ group.db }}</span>
          <span class="muted">{{ group.tableCount }}</span>
        </div>
      </div>
    </div>

    <div>
      <div class="card">
        <div class="card-head">
          <div class="card-title">表清单</div>
          <div class="card-sub">
            共 {{ visibleCount }} 张<span v-if="data?.statsSource === 'MASTER_METRICS'">，行数/大小为 master 统计估算</span>
          </div>
          <div class="card-actions">
            <input v-model="keyword" class="input" placeholder="搜索表名" style="width: 180px" />
            <button class="btn sm" @click="load" :disabled="loading">
              <span v-if="loading" class="spinner"></span>刷新
            </button>
            <button class="btn primary sm" @click="showCreate = true">新建表</button>
          </div>
        </div>

        <div v-if="error" class="banner error" style="margin: 12px">{{ error }}</div>

        <div class="card-body flush">
          <div v-if="loading && !data" class="loading"><span class="spinner"></span>加载表清单…</div>
          <div v-else-if="!groups.length" class="empty">没有匹配的表</div>
          <div v-else class="table-list">
            <div v-for="group in groups" :key="group.db" class="db-group">
              <div class="db-head">
                <span class="caret">▾</span>
                <span>{{ group.db }}</span>
                <span class="db-count">{{ group.tables.length }} 张表</span>
                <button class="btn ghost sm db-drop" @click="deleteDb = group">删除库</button>
              </div>
              <div class="grid-wrap">
                <table class="grid">
                  <thead>
                    <tr>
                      <th>表名</th>
                      <th>完整名</th>
                      <th class="num">列数</th>
                      <th class="num">行数（估算）</th>
                      <th class="num">磁盘占用</th>
                      <th>标记</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="table in group.tables" :key="table.name" @click="openTable(table.name)" style="cursor: pointer">
                      <td class="mono">{{ table.shortName }}</td>
                      <td class="mono muted">{{ table.name }}</td>
                      <td class="num">{{ table.columnCount ?? '—' }}</td>
                      <td class="num">{{ formatCount(table.liveRowCount) }}</td>
                      <td class="num">{{ formatBytes(table.onDiskSize) }}</td>
                      <td>
                        <span v-if="table.impalaManaged" class="tag impala">impala 托管</span>
                      </td>
                      <td class="nowrap">
                        <span class="btn ghost sm">打开</span>
                        <button class="btn ghost sm" style="color: var(--danger)" @click.stop="deleteTable = table">删除</button>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div class="hint" style="margin-top: 8px">
        表名为 Kudu 中的完整名；<span class="mono">impala::</span> 前缀表示该表由 Impala 创建管理。
        「删除库」会删掉该前缀下的全部表（Kudu 没有真正的库），走异步任务，可在任务中心看进度。
      </div>
    </div>

    <CreateTableDialog v-if="showCreate" @close="showCreate = false" @created="onCreated" />

    <ConfirmDialog
      v-if="deleteTable"
      title="删除表"
      :message="`将永久删除表 ${deleteTable.name} 及其全部数据，此操作不可恢复。`"
      :expect="deleteTable.name"
      confirm-text="删除表"
      :busy="droppingTable"
      @confirm="confirmDropTable"
      @close="deleteTable = null"
    />

    <ConfirmDialog
      v-if="deleteDb"
      title="删除库"
      :message="dbDropMessage"
      :expect="deleteDb.db"
      confirm-text="删除整个库"
      :busy="droppingDb"
      @confirm="confirmDropDb"
      @close="deleteDb = null"
    />
  </div>
</template>
