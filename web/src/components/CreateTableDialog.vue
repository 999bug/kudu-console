<script setup>
import { computed, reactive, ref } from 'vue'
import Dialog from './Dialog.vue'
import { api } from '../api'
import { toast, toastError } from '../store'

const emit = defineEmits(['close', 'created'])

const TYPES = ['INT8', 'INT16', 'INT32', 'INT64', 'STRING', 'VARCHAR', 'BOOL', 'FLOAT', 'DOUBLE',
  'BINARY', 'DECIMAL', 'DATE', 'UNIXTIME_MICROS']
const NUMERIC = ['INT8', 'INT16', 'INT32', 'INT64', 'FLOAT', 'DOUBLE', 'DECIMAL', 'UNIXTIME_MICROS']

const step = ref(1)
const busy = ref(false)
const error = ref('')

const form = reactive({
  db: 'default',
  name: '',
  replicas: 1,
  columns: [
    { name: 'id', type: 'INT32', key: true, nullable: false, precision: null, scale: null, length: null, defaultValue: '', comment: '' }
  ],
  hash: [],
  rangeColumns: [],
  ranges: []
})

const tableName = computed(() => {
  const name = form.name.trim()
  if (!name) return ''
  const db = form.db.trim()
  return db ? db + '.' + name : name
})

const pkColumns = computed(() => form.columns.filter((c) => c.key).map((c) => c.name).filter(Boolean))

const payload = computed(() => {
  const ordered = [
    ...form.columns.filter((c) => c.key),
    ...form.columns.filter((c) => !c.key)
  ]
  return {
    name: tableName.value,
    numReplicas: Number(form.replicas) || 1,
    columns: ordered.map((c) => ({
      name: c.name.trim(),
      type: c.type,
      key: !!c.key,
      nullable: c.key ? false : !!c.nullable,
      precision: c.type === 'DECIMAL' ? Number(c.precision) || null : null,
      scale: c.type === 'DECIMAL' ? Number(c.scale) || 0 : null,
      length: c.type === 'VARCHAR' ? Number(c.length) || null : null,
      defaultValue: c.defaultValue === '' ? null : c.defaultValue,
      comment: c.comment || null
    })),
    hashPartitions: form.hash
      .filter((h) => h.columns.length > 0)
      .map((h) => ({ columns: h.columns, buckets: Number(h.buckets) || 2, seed: Number(h.seed) || 0 })),
    rangePartitionColumns: form.rangeColumns,
    rangePartitions: form.rangeColumns.length
      ? form.ranges.map((r) => ({ lower: compact(r.lower), upper: compact(r.upper) }))
      : []
  }
})

const ddlPreview = computed(() => {
  const p = payload.value
  const cols = p.columns.map((c) => {
    let attrs = ''
    if (c.type === 'DECIMAL') attrs = `(${c.precision || '?'},${c.scale ?? 0})`
    if (c.type === 'VARCHAR') attrs = `(${c.length || '?'})`
    return `  ${c.name} ${c.type}${attrs}${c.key ? ' PRIMARY KEY' : c.nullable ? '' : ' NOT NULL'}`
  })
  const parts = []
  p.hashPartitions.forEach((h) => parts.push(`HASH (${h.columns.join(', ')}) PARTITIONS ${h.buckets}`))
  if (p.rangePartitionColumns.length) {
    parts.push(`RANGE (${p.rangePartitionColumns.join(', ')})  ${p.rangePartitions.length} 个分区`)
  }
  return `CREATE TABLE ${p.name} (\n${cols.join(',\n')}\n)\nREPLICAS ${p.numReplicas}\n` + parts.join('\n')
})

function compact(bound) {
  const result = {}
  Object.entries(bound || {}).forEach(([key, value]) => {
    if (value !== '' && value !== null && value !== undefined) result[key] = value
  })
  return result
}

function addColumn() {
  form.columns.push({
    name: '', type: 'STRING', key: false, nullable: true,
    precision: null, scale: null, length: null, defaultValue: '', comment: ''
  })
}

function removeColumn(index) {
  form.columns.splice(index, 1)
}

function onTypeChange(column) {
  if (column.type === 'DECIMAL' && !column.precision) {
    column.precision = 12
    column.scale = 2
  }
  if (column.type === 'VARCHAR' && !column.length) {
    column.length = 64
  }
}

function addHash() {
  form.hash.push({ columns: pkColumns.value.slice(0, 1), buckets: 4, seed: 0 })
}

function removeHash(index) {
  form.hash.splice(index, 1)
}

function toggleHashColumn(hash, column) {
  const index = hash.columns.indexOf(column)
  if (index >= 0) hash.columns.splice(index, 1)
  else hash.columns.push(column)
}

function toggleRangeColumn(column) {
  const index = form.rangeColumns.indexOf(column)
  if (index >= 0) {
    form.rangeColumns.splice(index, 1)
  } else {
    form.rangeColumns.push(column)
  }
  rebuildRanges()
}

function rebuildRanges() {
  form.ranges = [{ lower: {}, upper: {} }]
  form.rangeColumns.forEach((column) => {
    form.ranges[0].lower[column] = ''
    form.ranges[0].upper[column] = ''
  })
}

function addRange() {
  const bound = { lower: {}, upper: {} }
  form.rangeColumns.forEach((column) => {
    bound.lower[column] = ''
    bound.upper[column] = ''
  })
  form.ranges.push(bound)
}

function removeRange(index) {
  form.ranges.splice(index, 1)
}

function validate() {
  if (!tableName.value) return '请填写表名'
  if (!/^[^\s]+$/.test(form.name.trim())) return '表名不能包含空格'
  if (!pkColumns.value.length) return '至少需要一个主键列'
  if (pkColumns.value.length !== new Set(pkColumns.value).size) return '主键列重复'
  const names = form.columns.map((c) => c.name.trim())
  if (names.some((n) => !n)) return '存在空列名'
  if (new Set(names).size !== names.length) return '列名重复'
  for (const column of form.columns) {
    if (column.type === 'DECIMAL' && (!column.precision || !column.scale && column.scale !== 0)) {
      return `列 ${column.name} 需要 precision/scale`
    }
    if (column.type === 'VARCHAR' && !column.length) return `列 ${column.name} 需要 length`
  }
  for (const hash of form.hash) {
    if (hash.columns.some((c) => !pkColumns.value.includes(c))) return 'hash 分区列必须来自主键'
  }
  if (form.rangeColumns.length) {
    const prefix = pkColumns.value.slice(0, form.rangeColumns.length)
    if (prefix.join(',') !== form.rangeColumns.join(',')) return 'range 分区列必须是主键的前缀'
  }
  return ''
}

function next() {
  error.value = ''
  if (step.value === 1) {
    const message = validate()
    if (message) {
      error.value = message
      return
    }
  }
  step.value = Math.min(3, step.value + 1)
}

function prev() {
  step.value = Math.max(1, step.value - 1)
}

async function submit() {
  error.value = validate()
  if (error.value) return
  busy.value = true
  try {
    await api.createTable(payload.value)
    toast('表已创建：' + tableName.value)
    emit('created', tableName.value)
  } catch (e) {
    error.value = e.message
    toastError(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Dialog title="新建表" wide @close="emit('close')">
    <div class="step-head">
      <div class="step" :class="{ active: step === 1, done: step > 1 }">1. 基本信息与列</div>
      <div class="step" :class="{ active: step === 2, done: step > 2 }">2. 分区</div>
      <div class="step" :class="{ active: step === 3 }">3. 预览提交</div>
    </div>

    <div v-if="error" class="banner error">{{ error }}</div>

    <div v-show="step === 1">
      <div class="row">
        <div class="field grow">
          <label class="label">库名（表名前缀）</label>
          <input v-model="form.db" class="input mono" placeholder="default" />
          <span class="hint">Kudu 没有库概念，完整表名为 db.table</span>
        </div>
        <div class="field grow">
          <label class="label">表名</label>
          <input v-model="form.name" class="input mono" placeholder="orders" spellcheck="false" />
          <span class="hint">{{ tableName || '—' }}</span>
        </div>
        <div class="field" style="width: 110px">
          <label class="label">副本数</label>
          <select v-model.number="form.replicas" class="select">
            <option :value="1">1</option>
            <option :value="3">3</option>
            <option :value="5">5</option>
          </select>
        </div>
      </div>

      <div class="section" style="margin-top: 16px">
        <div style="display: flex; align-items: center; margin-bottom: 8px">
          <div class="section-title" style="margin: 0">列定义</div>
          <div style="margin-left: auto">
            <button class="btn sm" @click="addColumn">添加列</button>
          </div>
        </div>

        <div class="col-editor-row col-editor-head">
          <div>列名</div><div>类型</div><div>属性</div><div>主键</div><div>可空</div><div>默认值</div><div></div>
        </div>

        <div v-for="(column, index) in form.columns" :key="index" class="col-editor-row" style="margin-bottom: 6px">
          <input v-model="column.name" class="input mono" placeholder="列名" />
          <select v-model="column.type" class="select mono" @change="onTypeChange(column)">
            <option v-for="type in TYPES" :key="type" :value="type">{{ type }}</option>
          </select>
          <div v-if="column.type === 'DECIMAL'" style="display: flex; gap: 4px; min-width: 0">
            <input v-model.number="column.precision" class="input" style="min-width: 0; flex: 1" placeholder="P" title="precision" />
            <input v-model.number="column.scale" class="input" style="min-width: 0; flex: 1" placeholder="S" title="scale" />
          </div>
          <div v-else-if="column.type === 'VARCHAR'">
            <input v-model.number="column.length" class="input" placeholder="length" />
          </div>
          <div v-else class="muted" style="font-size: 11px">—</div>
          <label class="checkbox"><input type="checkbox" v-model="column.key" /></label>
          <label class="checkbox">
            <input type="checkbox" v-model="column.nullable" :disabled="column.key" />
          </label>
          <input v-model="column.defaultValue" class="input" placeholder="可空" />
          <button class="icon-btn danger" @click="removeColumn(index)" title="删除该列">×</button>
        </div>
        <div class="hint" style="margin-top: 6px">
          主键列提交时会自动排到最前；非空且非主键的列必须填写默认值；DECIMAL 需 precision/scale，VARCHAR 需 length。
        </div>
      </div>
    </div>

    <div v-show="step === 2">
      <div class="section">
        <div style="display: flex; align-items: center; margin-bottom: 8px">
          <div class="section-title" style="margin: 0">Hash 分区</div>
          <div style="margin-left: auto">
            <button class="btn sm" @click="addHash" :disabled="!pkColumns.length">添加 Hash 组</button>
          </div>
        </div>
        <div v-if="!form.hash.length" class="muted" style="font-size: 12px">
          不添加时整表按单分区处理（测试环境可直接跳过）。
        </div>
        <div v-for="(hash, index) in form.hash" :key="index" class="filter-row" style="margin-bottom: 8px">
          <div style="width: 250px">
            <div class="row tight">
              <label v-for="column in pkColumns" :key="column" class="checkbox">
                <input
                  type="checkbox"
                  :checked="hash.columns.includes(column)"
                  @change="toggleHashColumn(hash, column)"
                />{{ column }}
              </label>
            </div>
          </div>
          <div class="field" style="width: 90px">
            <input v-model.number="hash.buckets" class="input" type="number" min="1" placeholder="buckets" />
          </div>
          <div class="field" style="width: 90px">
            <input v-model.number="hash.seed" class="input" type="number" placeholder="seed" />
          </div>
          <button class="icon-btn danger" @click="removeHash(index)">×</button>
        </div>
      </div>

      <div class="section">
        <div class="section-title">Range 分区（可选，列必须是主键前缀）</div>
        <div class="row tight">
          <label v-for="column in pkColumns" :key="column" class="checkbox">
            <input
              type="checkbox"
              :checked="form.rangeColumns.includes(column)"
              @change="toggleRangeColumn(column)"
            />{{ column }}
          </label>
        </div>
        <div v-if="form.rangeColumns.length" style="margin-top: 10px">
          <div class="filter-row" v-for="(range, index) in form.ranges" :key="index" style="margin-bottom: 6px">
            <span class="tag plain">分区 {{ index + 1 }}</span>
            <div v-for="column in form.rangeColumns" :key="column" class="field" style="width: 190px">
              <label class="label">{{ column }} 下界</label>
              <input v-model="range.lower[column]" class="input mono" placeholder="留空 = 无界" />
            </div>
            <div v-for="column in form.rangeColumns" :key="'u' + column" class="field" style="width: 190px">
              <label class="label">{{ column }} 上界</label>
              <input v-model="range.upper[column]" class="input mono" placeholder="留空 = 无界" />
            </div>
            <button class="icon-btn danger" @click="removeRange(index)" v-if="form.ranges.length > 1">×</button>
          </div>
          <button class="btn sm" @click="addRange">添加分区</button>
        </div>
      </div>
    </div>

    <div v-show="step === 3">
      <div class="section">
        <div class="section-title">提交内容</div>
        <div class="code-box">{{ ddlPreview }}</div>
      </div>
      <div class="section">
        <div class="section-title">请求 JSON</div>
        <div class="code-box">{{ JSON.stringify(payload, null, 2) }}</div>
      </div>
    </div>

    <template #foot>
      <button class="btn" @click="emit('close')" :disabled="busy">取消</button>
      <button v-if="step > 1" class="btn" @click="prev" :disabled="busy">上一步</button>
      <button v-if="step < 3" class="btn primary" @click="next">下一步</button>
      <button v-else class="btn primary" @click="submit" :disabled="busy">
        <span v-if="busy" class="spinner"></span>创建表
      </button>
    </template>
  </Dialog>
</template>
