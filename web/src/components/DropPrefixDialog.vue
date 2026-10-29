<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api'
import Dialog from './Dialog.vue'

const emit = defineEmits(['close', 'submitted'])

const prefix = ref('')
const typed = ref('')
const preview = ref(null)
const error = ref('')
const loading = ref(false)
const submitting = ref(false)

let timer = null
let seq = 0

const trimmed = computed(() => prefix.value.trim())
const canSubmit = computed(() => !loading.value && !submitting.value && !!preview.value
  && preview.value.count > 0 && typed.value === trimmed.value)

async function loadPreview() {
  const target = trimmed.value
  const ticket = ++seq
  loading.value = true
  error.value = ''
  try {
    const res = await api.previewDropPrefix(target)
    if (ticket === seq) preview.value = res
  } catch (e) {
    if (ticket === seq) error.value = e.message
  } finally {
    if (ticket === seq) loading.value = false
  }
}

watch(prefix, () => {
  typed.value = ''
  error.value = ''
  preview.value = null
  if (timer) clearTimeout(timer)
  seq++
  if (!trimmed.value) {
    loading.value = false
    return
  }
  loading.value = true
  timer = setTimeout(loadPreview, 300)
})

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  error.value = ''
  try {
    emit('submitted', await api.dropPrefix(trimmed.value, trimmed.value))
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}

onUnmounted(() => {
  if (timer) clearTimeout(timer)
  seq++
})
</script>

<template>
  <Dialog title="按前缀删除表" @close="emit('close')">
    <p style="margin: 0 0 12px; color: var(--text-2)">
      按完整表名前缀匹配后逐张删除，<strong>不区分大小写</strong>，同时匹配
      <code class="mono">impala::</code> 托管表；<code class="mono">sys.</code> /
      <code class="mono">kudu.</code> 系统表自动跳过。删除走异步任务，可在任务中心查看进度。
    </p>

    <div class="field">
      <label class="label">表名前缀</label>
      <input v-model="prefix" class="input mono" placeholder="例如 tmp_ 或 db." spellcheck="false" />
    </div>

    <div v-if="loading" class="hint" style="margin-top: 10px"><span class="spinner"></span> 正在匹配表…</div>
    <div v-else-if="error" class="banner error" style="margin-top: 10px">{{ error }}</div>
    <template v-else-if="preview">
      <div v-if="preview.count" class="banner warn" style="margin-top: 10px">
        匹配到 {{ preview.count }} 张表，提交后将全部删除，且不可恢复。
      </div>
      <div v-else class="banner info" style="margin-top: 10px">
        没有以该前缀开头的表（系统表不参与删除）。
      </div>
      <div v-if="preview.tables.length" class="prefix-list mono">
        <div v-for="name in preview.tables" :key="name">{{ name }}</div>
      </div>
      <div v-if="preview.systemSkipped" class="hint" style="margin-top: 6px">
        另有 {{ preview.systemSkipped }} 张系统表命中该前缀，已自动跳过。
      </div>
    </template>

    <div v-if="preview && preview.count" class="field" style="margin-top: 14px">
      <label class="label">请输入 <code class="mono">{{ preview.prefix }}</code> 以确认</label>
      <input v-model="typed" class="input mono" :placeholder="preview.prefix" spellcheck="false" />
    </div>

    <template #foot>
      <button class="btn" @click="emit('close')" :disabled="submitting">取消</button>
      <button class="btn danger-solid" :disabled="!canSubmit" @click="submit">
        <span v-if="submitting" class="spinner"></span>
        删除{{ preview && preview.count ? ` ${preview.count} 张表` : '' }}
      </button>
    </template>
  </Dialog>
</template>

<style scoped>
.prefix-list {
  margin-top: 8px;
  max-height: 180px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.7;
  background: var(--surface-2);
}
</style>
