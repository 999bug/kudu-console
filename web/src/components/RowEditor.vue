<script setup>
import { computed, reactive, ref } from 'vue'
import { microsToLocalInput, localInputToMicros } from '../format'

const props = defineProps({
  columns: { type: Array, required: true },
  primaryKey: { type: Array, required: true },
  mode: { type: String, default: 'create' }, // create | edit
  row: { type: Object, default: null },
  table: { type: String, default: '' }
})
const emit = defineEmits(['save', 'close'])

const op = ref('UPSERT')
const busy = ref(false)
const error = ref('')

const state = reactive({})
const nullFlags = reactive({})

const editableColumns = computed(() => props.columns)

function initValue(column) {
  if (props.mode === 'edit' && props.row) {
    const value = props.row[column.name]
    if (value === null || value === undefined) {
      nullFlags[column.name] = true
      return ''
    }
    if (column.type === 'UNIXTIME_MICROS') return microsToLocalInput(value)
    return String(value)
  }
  nullFlags[column.name] = false
  return ''
}

props.columns.forEach((column) => {
  state[column.name] = initValue(column)
})

function isPrimaryKey(name) {
  return props.primaryKey.includes(name)
}

function toggleNull(column) {
  if (nullFlags[column.name]) {
    state[column.name] = ''
  }
}

function buildValues() {
  const values = {}
  for (const column of editableColumns.value) {
    const isPk = isPrimaryKey(column.name)
    if (props.mode === 'edit' && isPk) {
      const original = props.row[column.name]
      values[column.name] = original === null || original === undefined ? null : original
      continue
    }
    if (props.mode === 'edit' && !isPk) {
      const original = props.row[column.name]
      const originalText = original === null || original === undefined
        ? ''
        : column.type === 'UNIXTIME_MICROS' ? microsToLocalInput(original) : String(original)
      const current = nullFlags[column.name] ? '' : String(state[column.name] ?? '')
      const wasNull = original === null || original === undefined
      if (!wasNull && !nullFlags[column.name] && originalText === current) {
        continue // 未修改的列不提交
      }
      if (wasNull && nullFlags[column.name]) {
        continue
      }
    }
    const raw = state[column.name]
    if (nullFlags[column.name]) {
      values[column.name] = null
      continue
    }
    if (raw === '' || raw === null || raw === undefined) {
      if (isPk) {
        throw new Error(`主键列 ${column.name} 不能为空`)
      }
      if (!column.nullable && props.mode === 'create') {
        throw new Error(`非空列 ${column.name} 不能为空`)
      }
      continue
    }
    if (column.type === 'UNIXTIME_MICROS') {
      const micros = localInputToMicros(raw)
      if (micros === null) throw new Error(`列 ${column.name} 的时间格式不正确`)
      values[column.name] = micros
    } else {
      values[column.name] = String(raw)
    }
  }
  return values
}

function save() {
  error.value = ''
  try {
    const values = buildValues()
    if (!Object.keys(values).length) {
      throw new Error('没有需要提交的字段')
    }
    for (const pk of props.primaryKey) {
      if (!(pk in values) || values[pk] === null) {
        if (props.mode === 'create') throw new Error(`缺少主键列 ${pk}`)
      }
    }
    emit('save', { op: props.mode === 'edit' ? 'UPDATE' : op.value, rows: [values] })
  } catch (e) {
    error.value = e.message
  }
}
</script>

<template>
  <div class="drawer-wrap" @mousedown.self="emit('close')">
    <div class="drawer">
      <div class="drawer-head">
        <div>
          <div class="modal-title">{{ mode === 'edit' ? '编辑行' : '新增行' }}</div>
          <div class="hint mono">{{ table }}</div>
        </div>
        <button class="btn ghost sm" style="margin-left: auto" @click="emit('close')">关闭</button>
      </div>

      <div class="drawer-body">
        <div v-if="error" class="banner error" style="margin-bottom: 0">{{ error }}</div>

        <div v-if="mode === 'create'" class="field">
          <label class="label">写入方式</label>
          <select v-model="op" class="select">
            <option value="UPSERT">UPSERT（存在则覆盖）</option>
            <option value="INSERT">INSERT（主键冲突则失败）</option>
          </select>
        </div>

        <div v-for="column in editableColumns" :key="column.name" class="field">
          <label class="label">
            <span class="mono">{{ column.name }}</span>
            <span class="tag" style="margin-left: 6px">{{ column.type }}</span>
            <span v-if="isPrimaryKey(column.name)" class="tag pk" style="margin-left: 4px">PK</span>
            <span v-if="!column.nullable" class="hint" style="margin-left: 6px">非空</span>
          </label>

          <select
            v-if="column.type === 'BOOL'"
            v-model="state[column.name]"
            class="select"
            :disabled="(mode === 'edit' && isPrimaryKey(column.name)) || nullFlags[column.name]"
          >
            <option value="">（未设置）</option>
            <option value="true">true</option>
            <option value="false">false</option>
          </select>

          <input
            v-else-if="column.type === 'DATE'"
            v-model="state[column.name]"
            type="date"
            class="input"
            :disabled="(mode === 'edit' && isPrimaryKey(column.name)) || nullFlags[column.name]"
          />

          <input
            v-else-if="column.type === 'UNIXTIME_MICROS'"
            v-model="state[column.name]"
            type="datetime-local"
            step="1"
            class="input"
            :disabled="(mode === 'edit' && isPrimaryKey(column.name)) || nullFlags[column.name]"
          />

          <textarea
            v-else-if="column.type === 'BINARY'"
            v-model="state[column.name]"
            class="textarea"
            rows="2"
            placeholder="base64 编码的二进制值"
            :disabled="(mode === 'edit' && isPrimaryKey(column.name)) || nullFlags[column.name]"
          ></textarea>

          <input
            v-else
            v-model="state[column.name]"
            class="input"
            :class="{ mono: column.type === 'STRING' || column.type === 'VARCHAR' || isPrimaryKey(column.name) }"
            :placeholder="column.type === 'DECIMAL' ? '例如 12.34' : column.type.startsWith('INT') || column.type === 'FLOAT' || column.type === 'DOUBLE' ? '数字' : '文本'"
            :disabled="(mode === 'edit' && isPrimaryKey(column.name)) || nullFlags[column.name]"
          />

          <label class="checkbox" v-if="column.nullable">
            <input type="checkbox" v-model="nullFlags[column.name]" @change="toggleNull(column)" />
            设为 NULL
          </label>
          <span v-if="column.defaultValue" class="hint">默认值 {{ column.defaultValue }}</span>
        </div>

        <div class="hint" v-if="mode === 'edit'">
          主键列不可修改（Kudu 不支持改主键）；只提交改动过的列，未改动的列保持原值。
        </div>
      </div>

      <div class="drawer-foot">
        <button class="btn" @click="emit('close')" :disabled="busy">取消</button>
        <button class="btn primary" @click="save" :disabled="busy">
          {{ mode === 'edit' ? '保存修改' : '写入' }}
        </button>
      </div>
    </div>
  </div>
</template>
