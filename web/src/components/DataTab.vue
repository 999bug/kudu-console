<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { toast, toastError } from '../store'
import CellValue from './CellValue.vue'
import RowEditor from './RowEditor.vue'
import ConfirmDialog from './ConfirmDialog.vue'

const props = defineProps({
  detail: { type: Object, required: true }
})
const emit = defineEmits(['refresh'])

const filters = ref([])
const limit = ref(100)
const offset = ref(0)
const result = ref(null)
const loading = ref(false)
const error = ref('')

const editor = reactive({ open: false, mode: 'create', row: null })
const deleteTarget = ref(null)
const deleting = ref(false)

const table = computed(() => props.detail.name)
const columns = computed(() => props.detail.columns || [])

const MAX_OFFSET = 10000

function opsFor(column) {
  if (!column) return ['EQ']
  const t = column.type
  if (t === 'BOOL') return ['EQ', 'NE']
  if (t === 'STRING' || t === 'VARCHAR') return ['EQ', 'NE', 'LT', 'LTE', 'GT', 'GTE', 'IN', 'PREFIX', 'IS_NULL', 'IS_NOT_NULL']
  if (t === 'BINARY') return ['EQ', 'NE', 'IS_NULL', 'IS_NOT_NULL']
  return ['EQ', 'NE', 'LT', 'LTE', 'GT', 'GTE', 'IN', 'IS_NULL', 'IS_NOT_NULL']
}

function needsValue(op) {
  return op !== 'IS_NULL' && op !== 'IS_NOT_NULL'
}

function columnOf(name) {
  return columns.value.find((c) => c.name === name)
}

function addFilter() {
  const first = columns.value[0]
  filters.value.push({ column: first?.name || '', op: 'EQ', value: '' })
}

function removeFilter(index) {
  filters.value.splice(index, 1)
}

function resetFilters() {
  filters.value = []
  offset.value = 0
  run()
}

function buildFilters() {
  return filters.value
    .filter((f) => f.column && f.op)
    .map((f) => {
      if (!needsValue(f.op)) return { column: f.column, op: f.op }
      if (f.op === 'IN') {
        return {
          column: f.column,
          op: f.op,
          values: String(f.value || '').split(',').map((s) => s.trim()).filter(Boolean)
        }
      }
      return { column: f.column, op: f.op, value: String(f.value ?? '') }
    })
}

async function run(resetOffset = false) {
  if (resetOffset) offset.value = 0
  loading.value = true
  error.value = ''
  try {
    result.value = await api.query(table.value, {
      limit: limit.value,
      offset: offset.value,
      filters: buildFilters()
    })
  } catch (e) {
    error.value = e.message
    result.value = null
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editor.mode = 'create'
  editor.row = null
  editor.open = true
}

function openEdit(row) {
  editor.mode = 'edit'
  editor.row = row
  editor.open = true
}

async function onSave({ op, rows }) {
  try {
    const res = await api.mutate(table.value, { op, rows })
    if (res.failed > 0) {
      const first = res.errors?.[0]?.message || ''
      toast(`${res.failed} 行失败：${first}`, 'error', 9000)
    } else {
      toast(`已${op === 'DELETE' ? '删除' : op === 'UPDATE' ? '更新' : '写入'} ${res.applied} 行`)
    }
    editor.open = false
    await run()
    emit('refresh')
  } catch (e) {
    toastError(e)
  }
}

async function confirmDelete() {
  const row = deleteTarget.value
  if (!row) return
  deleting.value = true
  try {
    const key = {}
    props.detail.primaryKey.forEach((name) => {
      key[name] = row[name]
    })
    const res = await api.mutate(table.value, { op: 'DELETE', rows: [key] })
    if (res.failed > 0) {
      toast(res.errors?.[0]?.message || '删除失败', 'error', 9000)
    } else {
      toast('已删除 1 行')
    }
    deleteTarget.value = null
    await run()
    emit('refresh')
  } catch (e) {
    toastError(e)
  } finally {
    deleting.value = false
  }
}

const pageNo = computed(() => Math.floor(offset.value / limit.value) + 1)
const canPrev = computed(() => offset.value > 0)
const canNext = computed(() => result.value?.hasNext === true && offset.value + limit.value <= MAX_OFFSET)

const rowKeyText = computed(() => {
  if (!deleteTarget.value) return ''
  return props.detail.primaryKey.map((name) => `${name}=${deleteTarget.value[name]}`).join(', ')
})

onMounted(() => run())
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        <div class="card-title">条件查询</div>
        <div class="card-sub">仅支持 AND 组合（Kudu 无 OR 谓词）</div>
        <div class="card-actions">
          <button class="btn sm" @click="addFilter">添加条件</button>
          <button class="btn sm" @click="resetFilters">重置</button>
          <button class="btn primary sm" @click="run(true)" :disabled="loading">
            <span v-if="loading" class="spinner"></span>查询
          </button>
        </div>
      </div>

      <div class="filter-bar">
        <div v-if="!filters.length" class="filter-hint">未设置条件：按扫描顺序返回前 {{ limit }} 行。</div>
        <div v-for="(filter, index) in filters" :key="index" class="filter-row">
          <span class="tag plain" v-if="index > 0">AND</span>
          <select v-model="filter.column" class="select col mono">
            <option v-for="column in columns" :key="column.name" :value="column.name">
              {{ column.name }} · {{ column.type }}
            </option>
          </select>
          <select v-model="filter.op" class="select op">
            <option v-for="op in opsFor(columnOf(filter.column))" :key="op" :value="op">{{ op }}</option>
          </select>
          <input
            v-if="needsValue(filter.op)"
            v-model="filter.value"
            class="input val"
            :placeholder="filter.op === 'IN' ? '多个值用英文逗号分隔' : '过滤值'"
            @keyup.enter="run(true)"
          />
          <span v-else class="muted" style="font-size: 12px">无需值</span>
          <button class="icon-btn danger" @click="removeFilter(index)">×</button>
        </div>
      </div>

      <div v-if="error" class="banner error" style="margin: 12px">{{ error }}</div>

      <div class="card-body flush">
        <div v-if="loading && !result" class="loading"><span class="spinner"></span>查询中…</div>
        <div v-else-if="!result" class="empty">尚未查询</div>
        <div v-else-if="!result.rows.length" class="empty">没有匹配的数据</div>
        <div v-else class="grid-wrap" style="max-height: 56vh">
          <table class="grid">
            <thead>
              <tr>
                <th v-for="column in result.columns" :key="column.name">
                  <span class="mono">{{ column.name }}</span>
                  <span v-if="column.key" class="tag pk" style="margin-left: 4px">PK</span>
                  <div class="muted" style="font-weight: 400; font-size: 11px">{{ column.type }}</div>
                </th>
                <th style="width: 96px"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in result.rows" :key="index">
                <td v-for="column in result.columns" :key="column.name">
                  <CellValue :value="row[column.name]" :type="column.type" />
                </td>
                <td class="nowrap">
                  <button class="btn ghost sm" @click="openEdit(row)">编辑</button>
                  <button class="btn ghost sm" @click="deleteTarget = row">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="pager" v-if="result">
        <span class="pager-info">
          第 {{ pageNo }} 页 · 本页 {{ result.returned }} 行 · 扫描 {{ result.scannedRows }} 行 · {{ result.elapsedMs }} ms
          <span v-if="result.clientFiltered"> · 含客户端过滤</span>
          <span v-if="result.truncated" style="color: var(--warn)"> · 已达扫描上限，结果可能不完整</span>
        </span>
        <div class="pager-right">
          <select v-model.number="limit" class="select" style="width: 90px" @change="run(true)">
            <option :value="50">50 / 页</option>
            <option :value="100">100 / 页</option>
            <option :value="200">200 / 页</option>
            <option :value="500">500 / 页</option>
          </select>
          <button class="btn sm" :disabled="!canPrev || loading" @click="offset = Math.max(0, offset - limit); run()">上一页</button>
          <button class="btn sm" :disabled="!canNext || loading" @click="offset = offset + limit; run()">下一页</button>
          <button class="btn primary sm" @click="openCreate">新增行</button>
        </div>
      </div>
    </div>

    <RowEditor
      v-if="editor.open"
      :columns="columns"
      :primary-key="detail.primaryKey"
      :mode="editor.mode"
      :row="editor.row"
      :table="table"
      @save="onSave"
      @close="editor.open = false"
    />

    <ConfirmDialog
      v-if="deleteTarget"
      title="删除行"
      :message="`将删除主键为 ${rowKeyText} 的行，删除后不可恢复。`"
      confirm-text="删除"
      :busy="deleting"
      @confirm="confirmDelete"
      @close="deleteTarget = null"
    />
  </div>
</template>
