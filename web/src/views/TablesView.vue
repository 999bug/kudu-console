<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { toastError } from '../store'
import { formatBytes, formatCount } from '../format'
import CreateTableDialog from '../components/CreateTableDialog.vue'

const router = useRouter()
const loading = ref(false)
const error = ref('')
const data = ref(null)
const keyword = ref('')
const activeDb = ref('')
const showCreate = ref(false)

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
                      <td class="nowrap"><span class="btn ghost sm">打开</span></td>
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
      </div>
    </div>

    <CreateTableDialog v-if="showCreate" @close="showCreate = false" @created="onCreated" />
  </div>
</template>
