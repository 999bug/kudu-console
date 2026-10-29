<script setup>
import { computed, ref } from 'vue'
import Dialog from './Dialog.vue'

const props = defineProps({
  title: { type: String, default: '确认操作' },
  message: { type: String, default: '' },
  /** 需要原样输入的确认文本，为空则不校验 */
  expect: { type: String, default: '' },
  confirmText: { type: String, default: '确认执行' },
  danger: { type: Boolean, default: true },
  busy: { type: Boolean, default: false }
})
const emit = defineEmits(['confirm', 'close'])
const typed = ref('')

const canConfirm = computed(() => {
  if (props.busy) return false
  if (!props.expect) return true
  return typed.value === props.expect
})
</script>

<template>
  <Dialog :title="title" @close="emit('close')">
    <p style="margin: 0 0 12px; color: var(--text-2)">{{ message }}</p>
    <div v-if="expect" class="field">
      <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px">
        <label class="label">请输入 <code class="mono">{{ expect }}</code> 以确认</label>
        <button class="btn ghost sm" :disabled="busy" @click="typed = expect">一键填入</button>
      </div>
      <input v-model="typed" class="input mono" :placeholder="expect" spellcheck="false" />
    </div>
    <template #foot>
      <button class="btn" @click="emit('close')" :disabled="busy">取消</button>
      <button
        class="btn"
        :class="danger ? 'danger-solid' : 'primary'"
        :disabled="!canConfirm"
        @click="emit('confirm')"
      >
        <span v-if="busy" class="spinner"></span>{{ confirmText }}
      </button>
    </template>
  </Dialog>
</template>
