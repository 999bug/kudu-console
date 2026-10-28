<script setup>
import { computed, reactive, ref } from 'vue'
import { api } from '../api'
import { toast, toastError } from '../store'
import Dialog from './Dialog.vue'

const props = defineProps({ detail: { type: Object, required: true } })
const emit = defineEmits(['refresh'])

const columns = computed(() => props.detail.columns || [])
const showAdd = ref(false)
const busy = ref(false)
const error = ref('')

const form = reactive({
  name: '',
  type: 'STRING',
  nullable: true,
  precision: 12,
  scale: 2,
  length: 64,
  defaultValue: '',
  comment: ''
})

const TYPES = ['INT8', 'INT16', 'INT32', 'INT64', 'STRING', 'VARCHAR', 'BOOL', 'FLOAT', 'DOUBLE',
  'BINARY', 'DECIMAL', 'DATE', 'UNIXTIME_MICROS']

function openAdd() {
  error.value = ''
  form.name = ''
  form.type = 'STRING'
  form.nullable = true
  form.defaultValue = ''
  form.comment = ''
  showAdd.value = true
}

async function submitAdd() {
  error.value = ''
  if (!form.name.trim()) {
    error.value = '列名不能为空'
    return
  }
  const payload = {
    name: form.name.trim(),
    type: form.type,
    key: false,
    nullable: form.nullable,
    precision: form.type === 'DECIMAL' ? Number(form.precision) : null,
    scale: form.type === 'DECIMAL' ? Number(form.scale) : null,
    length: form.type === 'VARCHAR' ? Number(form.length) : null,
    defaultValue: form.defaultValue === '' ? null : form.defaultValue,
    comment: form.comment || null
  }
  busy.value = true
  try {
    await api.addColumn(props.detail.name, payload)
    toast('列已添加：' + payload.name)
    showAdd.value = false
    emit('refresh')
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

const dropTarget = ref(null)
const dropping = ref(false)
const dropError = ref('')

async function submitDrop() {
  if (!dropTarget.value) return
  dropping.value = true
  dropError.value = ''
  try {
    await api.dropColumn(props.detail.name, dropTarget.value)
    toast('列已删除：' + dropTarget.value)
    dropTarget.value = null
    emit('refresh')
  } catch (e) {
    dropError.value = e.message
  } finally {
    dropping.value = false
  }
}

function attrText(column) {
  if (column.type === 'DECIMAL') return `precision ${column.precision ?? '?'} / scale ${column.scale ?? 0}`
  if (column.type === 'VARCHAR') return `length ${column.length ?? '?'}`
  if (column.blockSizeBytes) return `block ${column.blockSizeBytes} B`
  return '—'
}
</script>

<template>
  <div>
    <div class="card">
      <div class="card-head">
        <div class="card-title">列定义</div>
        <div class="card-sub">{{ columns.length }} 列 · 主键 {{ detail.primaryKey.join(', ') }}</div>
        <div class="card-actions">
          <button class="btn sm" @click="openAdd">添加列</button>
        </div>
      </div>
      <div class="card-body flush grid-wrap">
        <table class="grid">
          <thead>
            <tr>
              <th>#</th>
              <th>列名</th>
              <th>类型</th>
              <th>属性</th>
              <th>主键</th>
              <th>可空</th>
              <th>编码</th>
              <th>压缩</th>
              <th>默认值</th>
              <th>注释</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(column, index) in columns" :key="column.name">
              <td class="num muted">{{ index + 1 }}</td>
              <td class="mono">{{ column.name }}<span v-if="column.key" class="tag pk" style="margin-left: 6px">PK</span></td>
              <td><span class="tag">{{ column.type }}</span></td>
              <td class="muted">{{ attrText(column) }}</td>
              <td>{{ column.key ? '是' : '' }}</td>
              <td>{{ column.nullable ? '是' : '否' }}</td>
              <td class="mono muted">{{ column.encoding || '—' }}</td>
              <td class="mono muted">{{ column.compression || '—' }}</td>
              <td class="mono">
                <span v-if="column.defaultValue === null || column.defaultValue === undefined" class="null">NULL</span>
                <span v-else>{{ column.defaultValue }}</span>
              </td>
              <td class="wrap muted">{{ column.comment || '' }}</td>
              <td>
                <button
                  v-if="!column.key"
                  class="btn ghost sm"
                  @click="dropTarget = column.name; dropError = ''"
                >删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="card">
      <div class="card-head"><div class="card-title">表信息</div></div>
      <div class="card-body">
        <dl class="kv">
          <dt>完整表名</dt><dd class="mono">{{ detail.name }}</dd>
          <dt>库</dt><dd class="mono">{{ detail.db }}</dd>
          <dt>Table ID</dt><dd class="mono">{{ detail.tableId || '—' }}</dd>
          <dt>副本数</dt><dd>{{ detail.numReplicas }}</dd>
          <dt>Impala 托管</dt><dd>{{ detail.impalaManaged ? '是' : '否' }}</dd>
          <dt>系统表</dt><dd>{{ detail.systemTable ? '是' : '否' }}</dd>
        </dl>
      </div>
    </div>

    <Dialog v-if="showAdd" title="添加列" @close="showAdd = false">
      <div v-if="error" class="banner error">{{ error }}</div>
      <div class="row">
        <div class="field grow">
          <label class="label">列名</label>
          <input v-model="form.name" class="input mono" placeholder="new_column" />
        </div>
        <div class="field grow">
          <label class="label">类型</label>
          <select v-model="form.type" class="select mono">
            <option v-for="type in TYPES" :key="type" :value="type">{{ type }}</option>
          </select>
        </div>
      </div>

      <div class="row" style="margin-top: 10px">
        <div class="field" v-if="form.type === 'DECIMAL'" style="width: 120px">
          <label class="label">Precision</label>
          <input v-model.number="form.precision" class="input" type="number" />
        </div>
        <div class="field" v-if="form.type === 'DECIMAL'" style="width: 100px">
          <label class="label">Scale</label>
          <input v-model.number="form.scale" class="input" type="number" />
        </div>
        <div class="field" v-if="form.type === 'VARCHAR'" style="width: 120px">
          <label class="label">Length</label>
          <input v-model.number="form.length" class="input" type="number" />
        </div>
        <div class="field grow">
          <label class="label">默认值</label>
          <input v-model="form.defaultValue" class="input" placeholder="非空列必填" />
        </div>
      </div>

      <label class="checkbox" style="margin-top: 10px">
        <input type="checkbox" v-model="form.nullable" />允许为空（非空列必须给默认值）
      </label>

      <div class="field" style="margin-top: 10px">
        <label class="label">注释</label>
        <input v-model="form.comment" class="input" placeholder="可选" />
      </div>

      <template #foot>
        <button class="btn" @click="showAdd = false" :disabled="busy">取消</button>
        <button class="btn primary" @click="submitAdd" :disabled="busy">
          <span v-if="busy" class="spinner"></span>添加
        </button>
      </template>
    </Dialog>

    <Dialog v-if="dropTarget" title="删除列" @close="dropTarget = null">
      <p style="margin-top: 0">
        将删除列 <code class="mono">{{ dropTarget }}</code>。Kudu 删列后数据不可恢复，且会重写表结构，表较大时可能较慢。
      </p>
      <div v-if="dropError" class="banner error">{{ dropError }}</div>
      <template #foot>
        <button class="btn" @click="dropTarget = null" :disabled="dropping">取消</button>
        <button class="btn danger-solid" @click="submitDrop" :disabled="dropping">
          <span v-if="dropping" class="spinner"></span>删除列
        </button>
      </template>
    </Dialog>
  </div>
</template>
